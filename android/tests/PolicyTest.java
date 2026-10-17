package dev.ipv6ctrl;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import static dev.ipv6ctrl.AddressPolicy.*;

public final class PolicyTest {
    public static void main(String[] args) throws Exception {
        var v6 = InetAddress.getByAddress(new byte[16]);
        var v4a = InetAddress.getByAddress(new byte[]{127,0,0,1});
        var v4b = InetAddress.getByAddress(new byte[]{127,0,0,2});
        var mixed = new InetAddress[]{v6,v4a,v4b};
        assert apply(mixed, Mode.SYSTEM) == mixed;
        assert Arrays.equals(apply(mixed, Mode.IPV4_FIRST), new InetAddress[]{v4a,v4b,v6});
        assert mixed[0] == v6 : "must not mutate resolver caches";
        assert Arrays.equals(apply(mixed, Mode.IPV4_ONLY), new InetAddress[]{v4a,v4b});
        try { apply(new InetAddress[]{v6}, Mode.IPV4_ONLY); throw new AssertionError("v6-only must fail"); }
        catch (UnknownHostException expected) {}
        assert apply(new InetAddress[]{v6}, Mode.IPV4_FIRST)[0] == v6;
        assert parse("broken") == Mode.SYSTEM;
        assert parse(null) == Mode.SYSTEM;
        assert parse("IPV4_ONLY") == Mode.IPV4_ONLY;
        System.out.println("Policy checks passed (8 cases; no network access)");
    }
}
