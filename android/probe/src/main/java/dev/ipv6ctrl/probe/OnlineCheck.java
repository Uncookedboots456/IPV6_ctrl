package dev.ipv6ctrl.probe;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

final class OnlineCheck {
    record Result(boolean reachable, String detail) {}

    static boolean validAddress(String value, int family) {
        // Restrict to literals before parsing, so an API response can never trigger DNS.
        if (value.isEmpty() || value.length() > 45 || !value.matches("[0-9a-fA-F:.]+")) return false;
        if (family == 4 && !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        if (family == 6 && !value.contains(":")) return false;
        try { return (InetAddress.getByName(value) instanceof Inet4Address) == (family == 4); }
        catch (Exception e) { return false; }
    }

    static Result query(int family) {
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(family == 6 ? "https://api6.ipify.org" : "https://api.ipify.org");
            connection = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(6000); connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "text/plain");
            connection.setRequestProperty("Connection", "close");
            if (connection.getResponseCode() != 200) return new Result(false, "HTTP_" + connection.getResponseCode());
            String value;
            try (var input = connection.getInputStream()) {
                byte[] bytes = new byte[65]; int length = 0, count;
                while (length < bytes.length && (count = input.read(bytes, length, bytes.length-length)) != -1) length += count;
                if (length > 64) return new Result(false, "INVALID_RESPONSE");
                value = new String(bytes, 0, length, StandardCharsets.US_ASCII).trim();
            }
            // Do not retain or broadcast the public IP address.
            return validAddress(value, family) ? new Result(true, "HTTPS_OK") : new Result(false, "INVALID_RESPONSE");
        } catch (Exception e) { return new Result(false, e.getClass().getSimpleName()); }
        finally { if (connection != null) connection.disconnect(); }
    }
}
