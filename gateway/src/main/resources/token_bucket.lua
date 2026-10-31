-- KEYS[1] = bucket key, ARGV[1] = capacity, ARGV[2] = refill rate in tokens per millisecond.
-- Runs atomically inside Redis, so concurrent requests cannot both spend the last token.
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])

-- Redis server time keeps every gateway replica on the same clock.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
local tokens = tonumber(bucket[1]) or capacity
local updated = tonumber(bucket[2]) or now

tokens = math.min(capacity, tokens + (now - updated) * rate)
local allowed = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated', now)
-- An idle bucket is full again after capacity / rate ms, so it can expire then.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate))
return allowed
