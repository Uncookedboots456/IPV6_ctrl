package dev.ipv6ctrl.probe;

public final class OnlineCheckTest {
    public static void main(String[] args) {
        assert OnlineCheck.validAddress("2001:db8::1", 6);
        assert OnlineCheck.validAddress("127.0.0.1", 4);
        assert !OnlineCheck.validAddress("127.0.0.1", 6);
        assert !OnlineCheck.validAddress("2001:db8::1", 4);
        assert !OnlineCheck.validAddress("999.1.1.1", 4);
        assert !OnlineCheck.validAddress("localhost", 6);
        assert !OnlineCheck.validAddress("<html>failure</html>", 6);
        assert !OnlineCheck.validAddress("::ffff:127.0.0.1", 6);
        assert !OnlineCheck.validAddress("", 6);
        System.out.println("Online API response checks passed (no network access)");
    }
}
