require_relative '../fastlane/versioning'

def expect(value, expected)
  raise "Expected #{expected.inspect}, got #{value.inspect}" unless value == expected
end

def rejects
  begin
    yield
  rescue RuntimeError, ArgumentError
    return
  end
  raise 'Expected invalid version to be rejected'
end

v = ClockroveVersioning
expect(v.next_code([1], now: v::EPOCH + 100), 100)
expect(v.next_code([200, '250'], now: v::EPOCH + 100), 251)
# A retry uses the just-published code as its floor even in the same second.
expect(v.next_code([100], now: v::EPOCH + 100), 101)
expect(v.next_code([], now: v::EPOCH - 1), 1)
rejects { v.next_code([v::MAX_CODE]) }
rejects { v.next_code(['not-a-code']) }
rejects { v.next_code([-1]) }
expect(v.production_name('v1.2.3'), '1.2.3')
%w[1.2.3 v01.2.3 v1.2 v1.2.3-beta].each { |tag| rejects { v.production_name(tag) } }
expect(v.internal_name('0.1.0', 12, 2), '0.1.0-internal.12.2')
puts 'Release version allocation and tag tests passed'
