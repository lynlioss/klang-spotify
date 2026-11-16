#!/usr/bin/env python3
"""Recover embedded FileDescriptorProto records from a native binary.

Install google.protobuf in a separate tool environment. The scanner verifies the
name field, wire layout, package, and descriptor types before retaining a record.
"""
import argparse
import json
import re
from pathlib import Path
from google.protobuf import descriptor_pb2, json_format


def varint(data, start):
    value = 0
    for shift in range(0, 70, 7):
        byte = data[start]
        start += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, start
    raise ValueError("Invalid varint")


def end_field(data, start):
    tag, cursor = varint(data, start)
    field, wire = tag >> 3, tag & 7
    if field not in range(1, 15):
        raise ValueError("End of descriptor")
    if wire == 2:
        size, cursor = varint(data, cursor)
        if size > 8_000_000 or cursor + size > len(data):
            raise ValueError("Invalid field size")
        return field, cursor + size
    if wire == 0:
        _, cursor = varint(data, cursor)
        return field, cursor
    raise ValueError("Unexpected descriptor field")


def render(fd):
    lines = [f'// Recovered descriptor. Original file: {fd.name}',
             f'syntax = "{fd.syntax or "proto2"}";']
    if fd.package:
        lines.append(f'package {fd.package};')
    lines += [f'import "{name}";' for name in fd.dependency]
    scalar = {value.number: value.name.removeprefix("TYPE_").lower()
              for value in descriptor_pb2.FieldDescriptorProto.Type.DESCRIPTOR.values}
    labels = {1: "optional ", 2: "required ", 3: "repeated "}
    def enum(value, indent):
        out = [indent + f'enum {value.name} {{']
        out += [indent + f'  {item.name} = {item.number};' for item in value.value]
        return out + [indent + '}']
    def message(value, indent):
        out = [indent + f'message {value.name} {{']
        for item in value.nested_type:
            out += message(item, indent + '  ')
        for item in value.enum_type:
            out += enum(item, indent + '  ')
        ordinary = [f for f in value.field if not f.HasField('oneof_index')]
        def field(item, prefix):
            kind = item.type_name or scalar[item.type]
            return prefix + f'{kind} {item.name} = {item.number};'
        for item in ordinary:
            prefix = indent + '  ' + (labels[item.label] if item.label == 3 or fd.syntax != 'proto3' else '')
            out.append(field(item, prefix))
        for i, group in enumerate(value.oneof_decl):
            out.append(indent + f'  oneof {group.name} {{')
            out += [field(item, indent + '    ') for item in value.field
                    if item.HasField('oneof_index') and item.oneof_index == i]
            out.append(indent + '  }')
        return out + [indent + '}']
    for value in fd.enum_type:
        lines += enum(value, '')
    for value in fd.message_type:
        lines += message(value, '')
    for service in fd.service:
        lines.append(f'service {service.name} {{')
        for method in service.method:
            lines.append(f'  rpc {method.name} ({"stream " if method.client_streaming else ""}{method.input_type}) returns ({"stream " if method.server_streaming else ""}{method.output_type});')
        lines.append('}')
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('binary', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    data = args.binary.read_bytes()
    found = {}
    for match in re.finditer(rb'[A-Za-z0-9_./-]+\.proto', data):
        for candidate in range(match.start() - 5, match.start() + 5):
            start = candidate
            if start < 0 or data[start] != 10:
                continue
            try:
                size, cursor = varint(data, start + 1)
                if cursor + size != match.end() or not match.start() <= cursor <= match.start() + 4:
                    continue
                name = data[cursor:cursor+size].decode()
                cursor = start
                last = cursor
                while cursor - start < 8_000_000:
                    try:
                        _, cursor = end_field(data, cursor)
                        last = cursor
                    except (ValueError, IndexError):
                        break
                fd = descriptor_pb2.FileDescriptorProto()
                fd.ParseFromString(data[start:last])
                if fd.name != name or not fd.package or not (fd.message_type or fd.enum_type or fd.service):
                    continue
                if name in found:
                    continue
                safe = name.replace('/', '__')
                (args.output / safe).write_text(render(fd))
                (args.output / (safe + '.json')).write_text(json.dumps(json_format.MessageToDict(fd, preserving_proto_field_name=True), indent=2) + '\n')
                (args.output / (safe + '.descriptor.bin')).write_bytes(data[start:last])
                found[name] = {'name': name, 'package': fd.package, 'offset': start,
                               'offsetHex': hex(start), 'bytes': last-start,
                               'messages': len(fd.message_type), 'services': len(fd.service)}
            except Exception:
                continue
    (args.output / 'index.json').write_text(json.dumps(list(found.values()), indent=2) + '\n')
    print(json.dumps({'descriptors': len(found), 'files': list(found)[:35]}, indent=2))


if __name__ == '__main__':
    main()
