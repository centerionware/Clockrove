module ClockroveVersioning
  MAX_CODE = 2_100_000_000
  EPOCH = Time.utc(2020, 1, 1).to_i

  def self.next_code(published, now: Time.now.to_i)
    codes = published.map { |value| Integer(value) }
    raise 'Invalid published version code' if codes.any?(&:negative?)
    candidate = [(codes.max || 0) + 1, now - EPOCH, 1].max
    raise 'Android versionCode capacity exceeded' if candidate > MAX_CODE
    candidate
  end

  def self.production_name(tag)
    match = /\Av((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))\z/.match(tag)
    raise 'Production tags must be vMAJOR.MINOR.PATCH' unless match
    match[1]
  end

  def self.internal_name(base, run, attempt)
    name = "#{base}-internal.#{Integer(run)}.#{Integer(attempt)}"
    raise 'Android versionName too long' if name.length > 50
    name
  end
end
