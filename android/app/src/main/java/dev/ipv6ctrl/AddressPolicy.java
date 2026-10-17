package dev.ipv6ctrl;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** Pure policy logic: never writes procfs, invokes a shell, or performs DNS. */
public final class AddressPolicy {
    public enum Mode { SYSTEM, IPV4_FIRST, IPV4_ONLY }

    public static Mode parse(String value) {
        try { return Mode.valueOf(value); }
        catch (IllegalArgumentException | NullPointerException e) { return Mode.SYSTEM; }
    }

    public static InetAddress[] apply(InetAddress[] addresses, Mode mode) throws UnknownHostException {
        if (mode == Mode.SYSTEM) return addresses;
        if (mode == Mode.IPV4_FIRST) {
            InetAddress[] copy = addresses.clone();
            Arrays.sort(copy, Comparator.comparingInt(a -> a instanceof Inet4Address ? 0 : 1));
            return copy;
        }
        ArrayList<InetAddress> ipv4 = new ArrayList<>();
        for (InetAddress address : addresses) if (address instanceof Inet4Address) ipv4.add(address);
        if (ipv4.isEmpty()) throw new UnknownHostException("IPv6 filtered by IPV6 Ctrl");
        return ipv4.toArray(new InetAddress[0]);
    }

    private AddressPolicy() {}
}
