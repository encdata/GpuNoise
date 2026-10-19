#!/usr/bin/env python3
"""Compare saved Minecraft chunk block states for paired CPU/GPU worldgen runs."""

from __future__ import annotations

import gzip
import math
import struct
import zlib
from pathlib import Path
from typing import Any, Iterator


class NbtReader:
    def __init__(self, data: bytes):
        self.data = data
        self.offset = 0

    def take(self, size: int) -> bytes:
        end = self.offset + size
        if size < 0 or end > len(self.data):
            raise ValueError(f"truncated NBT at byte {self.offset}: need {size} bytes")
        value = self.data[self.offset:end]
        self.offset = end
        return value

    def number(self, fmt: str) -> int | float:
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.take(size))[0]

    def string(self) -> str:
        length = int(self.number(">H"))
        return self.take(length).decode("utf-8")

    def payload(self, tag_type: int) -> Any:
        if tag_type == 1:
            return int(self.number(">b"))
        if tag_type == 2:
            return int(self.number(">h"))
        if tag_type == 3:
            return int(self.number(">i"))
        if tag_type == 4:
            return int(self.number(">q"))
        if tag_type == 5:
            return float(self.number(">f"))
        if tag_type == 6:
            return float(self.number(">d"))
        if tag_type == 7:
            length = int(self.number(">i"))
            return self.take(length)
        if tag_type == 8:
            return self.string()
        if tag_type == 9:
            element_type = int(self.number(">B"))
            length = int(self.number(">i"))
            if length < 0:
                raise ValueError("negative NBT list length")
            return [self.payload(element_type) for _ in range(length)]
        if tag_type == 10:
            compound: dict[str, Any] = {}
            while True:
                child_type = int(self.number(">B"))
                if child_type == 0:
                    return compound
                name = self.string()
                compound[name] = self.payload(child_type)
        if tag_type == 11:
            length = int(self.number(">i"))
            if length < 0:
                raise ValueError("negative NBT int-array length")
            return list(struct.unpack(f">{length}i", self.take(length * 4)))
        if tag_type == 12:
            length = int(self.number(">i"))
            if length < 0:
                raise ValueError("negative NBT long-array length")
            return list(struct.unpack(f">{length}q", self.take(length * 8)))
        raise ValueError(f"unknown NBT tag type {tag_type}")

    def root(self) -> dict[str, Any]:
        tag_type = int(self.number(">B"))
        if tag_type != 10:
            raise ValueError(f"chunk NBT root must be a compound, got tag {tag_type}")
        self.string()
        root = self.payload(tag_type)
        if self.offset != len(self.data):
            raise ValueError(f"{len(self.data) - self.offset} trailing bytes after chunk NBT")
        return root


def _decompress(compression: int, payload: bytes) -> bytes:
    if compression == 1:
        return gzip.decompress(payload)
    if compression == 2:
        return zlib.decompress(payload)
    if compression == 3:
        return payload
    raise ValueError(f"unsupported Anvil chunk compression type {compression}")


def _iter_region(path: Path) -> Iterator[tuple[tuple[int, int], dict[str, Any]]]:
    raw = path.read_bytes()
    if len(raw) < 8192:
        raise ValueError(f"truncated region header: {path}")
    region_parts = path.stem.split(".")
    if len(region_parts) != 3 or region_parts[0] != "r":
        raise ValueError(f"invalid region filename: {path.name}")
    region_x, region_z = int(region_parts[1]), int(region_parts[2])
    for slot in range(1024):
        entry = int.from_bytes(raw[slot * 4:slot * 4 + 4], "big")
        sector_offset, sector_count = entry >> 8, entry & 0xFF
        if sector_offset == 0 or sector_count == 0:
            continue
        start = sector_offset * 4096
        if start + 5 > len(raw):
            raise ValueError(f"invalid chunk sector in {path}, slot {slot}")
        length = int.from_bytes(raw[start:start + 4], "big")
        compression_byte = raw[start + 4]
        external = (compression_byte & 0x80) != 0
        compression = compression_byte & 0x7F
        local_x, local_z = slot & 31, slot >> 5
        chunk_x, chunk_z = region_x * 32 + local_x, region_z * 32 + local_z
        if external:
            sidecar = path.parent / f"c.{chunk_x}.{chunk_z}.mcc"
            compressed = sidecar.read_bytes()
        else:
            if length < 1 or start + 4 + length > len(raw):
                raise ValueError(f"invalid chunk length {length} in {path}, slot {slot}")
            compressed = raw[start + 5:start + 4 + length]
        nbt = NbtReader(_decompress(compression, compressed)).root()
        yield (chunk_x, chunk_z), nbt


def _state_signature(state: dict[str, Any]) -> tuple[str, tuple[tuple[str, str], ...]]:
    name = state.get("Name")
    if not isinstance(name, str):
        raise ValueError(f"block-state palette entry has no string Name: {state!r}")
    properties = state.get("Properties", {})
    if not isinstance(properties, dict):
        raise ValueError(f"invalid block-state Properties compound: {properties!r}")
    return name, tuple(sorted((str(key), str(value)) for key, value in properties.items()))


def _section_states(section: dict[str, Any]) -> tuple[int, list[tuple[str, tuple[tuple[str, str], ...]]]]:
    section_y = int(section["Y"])
    block_states = section.get("block_states")
    air = ("minecraft:air", ())
    if block_states is None:
        return section_y, [air] * 4096
    if not isinstance(block_states, dict):
        raise ValueError(f"invalid block_states section: {block_states!r}")
    palette = block_states.get("palette")
    if not isinstance(palette, list) or not palette:
        raise ValueError("block-state palette is missing or empty")
    signatures = [_state_signature(state) for state in palette]
    packed = block_states.get("data")
    if packed is None:
        if len(signatures) != 1:
            raise ValueError("multi-entry block-state palette has no packed data")
        return section_y, [signatures[0]] * 4096
    if not isinstance(packed, list):
        raise ValueError("block-state packed data is not a long array")
    bits = max(4, (len(signatures) - 1).bit_length())
    values_per_long = 64 // bits
    mask = (1 << bits) - 1
    required = math.ceil(4096 / values_per_long)
    if len(packed) < required:
        raise ValueError(f"block-state storage has {len(packed)} longs, requires {required}")
    decoded: list[tuple[str, tuple[tuple[str, str], ...]]] = []
    for index in range(4096):
        word = packed[index // values_per_long] & 0xFFFFFFFFFFFFFFFF
        palette_index = (word >> ((index % values_per_long) * bits)) & mask
        if palette_index >= len(signatures):
            raise ValueError(f"block-state palette index {palette_index} exceeds {len(signatures)} entries")
        decoded.append(signatures[palette_index])
    return section_y, decoded


def _chunk_sections(nbt: dict[str, Any]) -> dict[int, list[tuple[str, tuple[tuple[str, str], ...]]]]:
    sections = nbt.get("sections", [])
    if not isinstance(sections, list):
        raise ValueError("chunk sections tag is not a list")
    result: dict[int, list[tuple[str, tuple[tuple[str, str], ...]]]] = {}
    for section in sections:
        if not isinstance(section, dict):
            raise ValueError("chunk section list contains a non-compound")
        y, states = _section_states(section)
        if y in result:
            raise ValueError(f"duplicate section Y={y}")
        result[y] = states
    return result


def _canonical_tag(value: Any) -> Any:
    if isinstance(value, dict):
        return tuple(sorted((str(key), _canonical_tag(child)) for key, child in value.items()))
    if isinstance(value, list):
        return tuple(_canonical_tag(child) for child in value)
    return value


def compare_worlds(cpu_world: Path, gpu_world: Path) -> tuple[int, int, int]:
    """Raise on any block-state difference; return chunks, sections, block count."""
    cpu_region_dir, gpu_region_dir = cpu_world / "region", gpu_world / "region"
    if not cpu_region_dir.is_dir() or not gpu_region_dir.is_dir():
        raise RuntimeError(f"missing Anvil region directory for CPU or GPU world: {cpu_world}, {gpu_world}")
    cpu_regions = {path.name: path for path in cpu_region_dir.glob("r.*.*.mca")}
    gpu_regions = {path.name: path for path in gpu_region_dir.glob("r.*.*.mca")}
    if cpu_regions.keys() != gpu_regions.keys():
        raise RuntimeError(
            "CPU/GPU region-file sets differ: "
            f"missingGPU={sorted(cpu_regions.keys() - gpu_regions.keys())[:8]}, "
            f"missingCPU={sorted(gpu_regions.keys() - cpu_regions.keys())[:8]}"
        )

    compared_chunks = 0
    compared_sections = 0
    compared_blocks = 0
    for region_name in sorted(cpu_regions):
        cpu_chunks = iter(_iter_region(cpu_regions[region_name]))
        gpu_chunks = iter(_iter_region(gpu_regions[region_name]))
        while True:
            cpu_chunk = next(cpu_chunks, None)
            gpu_chunk = next(gpu_chunks, None)
            if cpu_chunk is None or gpu_chunk is None:
                if cpu_chunk is not None or gpu_chunk is not None:
                    raise RuntimeError(f"CPU/GPU saved chunk sets differ in region {region_name}")
                break
            cpu_pos, cpu_nbt = cpu_chunk
            gpu_pos, gpu_nbt = gpu_chunk
            if cpu_pos != gpu_pos:
                raise RuntimeError(
                    f"CPU/GPU saved chunk positions differ in {region_name}: {cpu_pos} vs {gpu_pos}"
                )
            compared_chunks += 1
            cpu_fluid_ticks = tuple(sorted((_canonical_tag(tick) for tick in cpu_nbt.get("fluid_ticks", [])), key=repr))
            gpu_fluid_ticks = tuple(sorted((_canonical_tag(tick) for tick in gpu_nbt.get("fluid_ticks", [])), key=repr))
            if cpu_fluid_ticks != gpu_fluid_ticks:
                raise RuntimeError(
                    f"CPU/GPU scheduled fluid ticks differ at chunk {cpu_pos}: "
                    f"CPU={cpu_fluid_ticks[:4]}, GPU={gpu_fluid_ticks[:4]}"
                )
            cpu_sections = _chunk_sections(cpu_nbt)
            gpu_sections = _chunk_sections(gpu_nbt)
            if cpu_sections.keys() != gpu_sections.keys():
                raise RuntimeError(
                    f"CPU/GPU section sets differ at chunk {cpu_pos}: "
                    f"CPU={sorted(cpu_sections)}, GPU={sorted(gpu_sections)}"
                )
            for section_y in sorted(cpu_sections):
                expected, actual = cpu_sections[section_y], gpu_sections[section_y]
                if len(expected) != len(actual):
                    raise RuntimeError(f"CPU/GPU section size differs at {cpu_pos}, section Y={section_y}")
                compared_sections += 1
                compared_blocks += len(expected)
                for index, (cpu_state, gpu_state) in enumerate(zip(expected, actual)):
                    if cpu_state == gpu_state:
                        continue
                    x, z, y = index & 15, (index >> 4) & 15, (index >> 8) & 15
                    world_pos = (cpu_pos[0] * 16 + x, section_y * 16 + y, cpu_pos[1] * 16 + z)
                    raise RuntimeError(
                        f"CPU/GPU block state mismatch at world {world_pos}: CPU={cpu_state}, GPU={gpu_state}"
                    )
    if compared_chunks == 0:
        raise RuntimeError(f"no saved chunks found under {cpu_region_dir}")
    return compared_chunks, compared_sections, compared_blocks
