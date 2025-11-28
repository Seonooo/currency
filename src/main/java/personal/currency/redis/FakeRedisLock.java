package personal.currency.redis;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class FakeRedisLock {
    // "Key가 존재하면 넣지 않고(false), 없으면 넣는다(true)" -> Atomic 연산 보장
    private final ConcurrentHashMap<String, String> lockStore = new ConcurrentHashMap<>();

    public boolean tryLock(String key) {
        return lockStore.putIfAbsent(key, "LOCKED") == null;
    }

    public void unlock(String key) {
        lockStore.remove(key);
    }
}