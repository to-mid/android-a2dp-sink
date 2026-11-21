#!/usr/bin/env python3
"""Emulate the MIUI guard with an operator-supplied original library (requires unicorn)."""
import hashlib
import pathlib
import re
import sys

from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import UC_ARM64_REG_X8, UC_ARM64_REG_X19, UC_ARM64_REG_X26

source = (pathlib.Path(__file__).resolve().parents[1] /
          'app/src/local/bluetooth/speaker/NativeCompatibility.java').read_text(encoding='utf8')
original = pathlib.Path(sys.argv[1]).read_bytes()
original_hash = re.search(r'ORIGINAL_HASH = "(.*?)"', source)[1]
guarded_hash = re.search(r'(?m)^    private static final String GUARDED_HASH = "(.*?)"', source)[1]
assert hashlib.sha256(original).hexdigest() == original_hash, 'Unexpected vendor build'
offset = int(re.search(r'OFFSET = (0x[0-9a-f]+)', source)[1], 16)
expected = bytes.fromhex(re.search(r'ORIGINAL = hex\("(.*?)"\)', source)[1])
patch = bytes.fromhex(re.search(r'PATCH = hex\("(.*?)"\)', source)[1])
assert original[offset:offset + len(expected)] == expected
guarded = bytearray(original)
guarded[offset:offset + len(patch)] = patch
connect_offset = int(re.search(r'CONNECT_OFFSET = (0x[0-9a-f]+)', source)[1], 16)
connect_expected = bytes.fromhex(re.search(r'CONNECT_ORIGINAL = hex\("(.*?)"\)', source)[1])
connect_patch = bytes.fromhex(re.search(r'CONNECT_PATCH = hex\("(.*?)"\)', source)[1])
assert original[connect_offset:connect_offset + len(connect_expected)] == connect_expected
assert len(connect_patch) == len(connect_expected)
guarded[connect_offset:connect_offset + len(connect_patch)] = connect_patch
sdp_offset = int(re.search(r'SDP_OFFSET = (0x[0-9a-f]+)', source)[1], 16)
sdp_expected = bytes.fromhex(re.search(r'SDP_ORIGINAL = hex\("(.*?)"\)', source)[1])
sdp_patch = bytes.fromhex(re.search(r'SDP_PATCH = hex\("(.*?)"\)', source)[1])
assert original[sdp_offset:sdp_offset + len(sdp_expected)] == sdp_expected
assert len(sdp_patch) == len(sdp_expected)
guarded[sdp_offset:sdp_offset + len(sdp_patch)] = sdp_patch
assert hashlib.sha256(guarded).hexdigest() == guarded_hash

for pointer in (0, 0x601000):
    machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    machine.mem_map(0, 0x800000)
    machine.mem_write(0, bytes(guarded))
    machine.reg_write(UC_ARM64_REG_X19, 0x600000)
    machine.mem_write(0x600370, pointer.to_bytes(8, 'little'))
    reached = []

    def stop_at_original_code(uc, address, size, unused):
        if address in (0x20c518, 0x20cbe4):
            reached.append(address)
            uc.emu_stop()

    machine.hook_add(UC_HOOK_CODE, stop_at_original_code)
    machine.emu_start(offset, 0x20cbe8, count=8)
    assert reached == [0x20cbe4 if pointer == 0 else 0x20c518]
    assert machine.reg_read(UC_ARM64_REG_X8) == pointer
    assert machine.reg_read(UC_ARM64_REG_X26) == 0x3e2000, 'Logging base must survive'
    assert machine.reg_read(UC_ARM64_REG_X19) == 0x600000, 'Device must survive'
    print('PASS: null list returns safely' if pointer == 0 else 'PASS: valid list follows original handler')

# Execute the complete patched entry, including the original address lookup,
# state query helpers, callee-saved registers and Android shadow call stack.
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
                                UC_ARM64_REG_X3, UC_ARM64_REG_X18, UC_ARM64_REG_X20,
                                UC_ARM64_REG_X29, UC_ARM64_REG_X30, UC_ARM64_REG_SP)

target = bytes.fromhex('14ac601afc68')
other = bytes.fromhex('102030405060')
for states, addresses, enabled, expect_queue in [
        ([0], [target], True, True),
        ([1], [target], True, False),
        ([2], [target], True, False),
        ([3], [target], True, False),
        ([4], [target], True, False),
        ([5], [target], True, True),
        ([1], [other], True, True),
        ([2, 1], [other, target], True, False),
        ([2, 0], [other, target], True, True),
        ([1], [target], False, False)]:
    machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    machine.mem_map(0, 0x800000)
    machine.mem_write(0, bytes(guarded))
    machine.mem_write(0x600000, target)
    machine.mem_write(0x3fd330, (1 if enabled else 0).to_bytes(8, 'little'))
    machine.mem_write(0x3fd398, bytes(8))
    machine.mem_write(0x3e3118, len(states).to_bytes(4, 'little'))
    for i, (state, address) in enumerate(zip(states, addresses)):
        slot = 0x3fcf18 + i * 0xc8
        handle = 0x601000 + i * 0x100
        machine.mem_write(slot + 1, address)
        machine.mem_write(slot + 8, handle.to_bytes(8, 'little'))
        machine.mem_write(handle, state.to_bytes(4, 'little'))
    machine.reg_write(UC_ARM64_REG_X0, 0x600000)
    machine.reg_write(UC_ARM64_REG_X18, 0x710000)
    machine.reg_write(UC_ARM64_REG_X19, 0x191919)
    machine.reg_write(UC_ARM64_REG_X20, 0x202020)
    machine.reg_write(UC_ARM64_REG_X29, 0x292929)
    machine.reg_write(UC_ARM64_REG_X30, 0x720000)
    machine.reg_write(UC_ARM64_REG_SP, 0x700000)
    reached = []

    def stop_on_queue_or_return(uc, address, size, unused):
        if address in (0x202128, 0x720000):
            reached.append(address)
            uc.emu_stop()

    machine.hook_add(UC_HOOK_CODE, stop_on_queue_or_return)
    machine.emu_start(connect_offset, 0x730000, count=1000)
    assert reached == [0x202128 if expect_queue else 0x720000], (states, addresses, reached)
    if expect_queue:
        assert machine.reg_read(UC_ARM64_REG_X0) == 0x110b
        assert machine.reg_read(UC_ARM64_REG_X1) == 0x600000
        assert machine.reg_read(UC_ARM64_REG_X2) == 0x3d3648
        assert machine.reg_read(UC_ARM64_REG_X3) == len(states)
    else:
        assert machine.reg_read(UC_ARM64_REG_X0) == (0 if enabled else 2)
    for register, expected in [(UC_ARM64_REG_X18, 0x710000), (UC_ARM64_REG_X19, 0x191919),
                               (UC_ARM64_REG_X20, 0x202020), (UC_ARM64_REG_X29, 0x292929),
                               (UC_ARM64_REG_X30, 0x720000), (UC_ARM64_REG_SP, 0x700000)]:
        assert machine.reg_read(register) == expected, (register, hex(machine.reg_read(register)))
    print('PASS: connection queue guard', states, 'enabled=' + str(enabled), 'enqueue=' + str(expect_queue))

# Run the entire shipped SDP callback with its actual patched instructions.
# Only allocator, SCB lookup, config storage and external dispatch are stubbed.
# A local Source must retain its original failure; a local Sink may attempt
# conservative AVDTP negotiation, never emit a connected-state event here.
from unicorn.arm64_const import UC_ARM64_REG_X21, UC_ARM64_REG_X22, UC_ARM64_REG_X23, UC_ARM64_REG_X24

def run_sdp(image, success, role, skip, record=True, valid=True):
    machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    machine.mem_map(0, 0x800000)
    machine.mem_write(0, bytes(image))
    scb, message, service = 0x600000, 0x602000, 0x603000
    machine.mem_write(scb + 0x4f2, role.to_bytes(2, 'little'))
    machine.mem_write(scb + 0x4f5, bytes([skip]))
    machine.mem_write(scb + 0x4b7, b'\x02')
    machine.mem_write(scb + 0x4aa, b'\xff\xff')
    machine.mem_write(service + 0x1a, b'\x03\x01')
    machine.reg_write(UC_ARM64_REG_X0, int(success))
    machine.reg_write(UC_ARM64_REG_X1, service if record else 0)
    machine.reg_write(UC_ARM64_REG_X18, 0x710000)
    machine.reg_write(UC_ARM64_REG_X30, 0x720000)
    machine.reg_write(UC_ARM64_REG_SP, 0x700000)
    preserved = [UC_ARM64_REG_X19, UC_ARM64_REG_X20, UC_ARM64_REG_X21,
                 UC_ARM64_REG_X22, UC_ARM64_REG_X23, UC_ARM64_REG_X24, UC_ARM64_REG_X29]
    for register in preserved: machine.reg_write(register, register * 0x101)
    dispatched = []

    def external(uc, address, size, unused):
        if address == 0x3d2d84:
            uc.reg_write(UC_ARM64_REG_X0, scb if valid else 0)
        elif address == 0x34b074:
            assert uc.reg_read(UC_ARM64_REG_X0) == 10
            uc.reg_write(UC_ARM64_REG_X0, message)
        elif address in (0x18fdc0, 0x102ac0):
            pass
        elif address == 0x180488:
            assert uc.reg_read(UC_ARM64_REG_X0) == message
            dispatched.append(int.from_bytes(uc.mem_read(message, 2), 'little'))
        elif address in (0x17f824, 0x720000):
            uc.emu_stop()
            return
        else:
            return
        uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_X30))

    from unicorn.arm64_const import UC_ARM64_REG_PC
    machine.hook_add(UC_HOOK_CODE, external)
    machine.emu_start(0x11393c, 0x730000, count=1000)
    for register in preserved: assert machine.reg_read(register) == register * 0x101
    assert machine.reg_read(UC_ARM64_REG_X18) == 0x710000
    assert machine.reg_read(UC_ARM64_REG_SP) == 0x700000
    return (dispatched, int.from_bytes(machine.mem_read(scb + 0x4aa, 2), 'little'),
            bytes(machine.mem_read(scb + 0x4f5, 1)))

for success, role, skip, record, valid in [
        (False, 0x110b, 0, True, True),
        (False, 0x110a, 0, True, True),
        (False, 0, 0, True, True),
        (False, 0x110a, 1, True, True),
        (False, 0x110b, 1, True, True),
        (True, 0x110b, 0, True, True),
        (True, 0x110a, 0, True, True),
        (True, 0x110b, 0, False, True),
        (False, 0x110b, 0, True, False)]:
    before = run_sdp(original, success, role, skip, record, valid)
    after = run_sdp(guarded, success, role, skip, record, valid)
    if not success and role == 0x110b and not skip and valid:
        assert before == ([0x1215], 0, b'\0')
        assert after == ([0x1214], 0x102, b'\0')
    else:
        assert before == after, (before, after)
    print('PASS: SDP callback', success, hex(role), skip, record, valid, after)
