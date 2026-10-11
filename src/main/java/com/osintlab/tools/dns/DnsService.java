package com.osintlab.tools.dns;

import com.osintlab.support.Inputs;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DNS lookups via the JDK's built-in JNDI DNS provider (uses the system resolver).
 */
@Service
public class DnsService implements OsintTool {

    private static final List<String> TYPES = List.of("A", "AAAA", "CNAME", "MX", "NS", "TXT", "SOA");

    @Override
    public String id() {
        return "dns";
    }

    @Override
    public Map<String, Object> run(String target) throws NamingException {
        return lookup(target);
    }

    public Map<String, Object> lookup(String rawDomain) throws NamingException {
        String domain = Inputs.domain(rawDomain);

        Map<String, List<String>> records = new LinkedHashMap<>();
        DirContext ctx = context();
        try {
            for (String type : TYPES) {
                List<String> values = query(ctx, domain, type);
                if (!values.isEmpty()) records.put(type, values);
            }
        } finally {
            ctx.close();
        }

        List<Map<String, String>> reverse = new ArrayList<>();
        for (String type : List.of("A", "AAAA")) {
            for (String ip : records.getOrDefault(type, List.of())) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("ip", ip);
                row.put("ptr", reverse(ip));
                reverse.add(row);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", domain);
        out.put("records", records);
        out.put("reverseDns", reverse);
        return out;
    }

    /** Single-type lookup, reusable by other tools (e.g. email analysis). */
    public List<String> query(String name, String type) throws NamingException {
        DirContext ctx = context();
        try {
            return query(ctx, name, type);
        } finally {
            ctx.close();
        }
    }

    /**
     * Like {@link #query(String, String)} but only an NXDOMAIN counts as "no records"; timeouts and
     * server failures throw, so callers such as blacklist checks can tell "not listed" from "no answer".
     */
    public List<String> queryStrict(String name, String type) throws NamingException {
        DirContext ctx = context();
        try {
            Attribute attr = ctx.getAttributes(name, new String[]{type}).get(type);
            if (attr == null) return List.of();
            List<String> out = new ArrayList<>();
            NamingEnumeration<?> values = attr.getAll();
            while (values.hasMore()) out.add(clean(type, String.valueOf(values.next())));
            return out;
        } catch (NameNotFoundException e) {
            return List.of();
        } finally {
            ctx.close();
        }
    }

    /** PTR lookup; returns null when the IP has no reverse record. */
    public String reverse(String ip) {
        try {
            String host = InetAddress.getByName(ip).getCanonicalHostName();
            return host.equals(ip) ? null : host;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> query(DirContext ctx, String name, String type) {
        try {
            Attribute attr = ctx.getAttributes(name, new String[]{type}).get(type);
            if (attr == null) return List.of();
            List<String> out = new ArrayList<>();
            NamingEnumeration<?> values = attr.getAll();
            while (values.hasMore()) {
                out.add(clean(type, String.valueOf(values.next())));
            }
            return out;
        } catch (NameNotFoundException e) {
            return List.of();
        } catch (NamingException e) {
            // Timeouts / SERVFAIL for a single type shouldn't fail the whole lookup
            return List.of();
        }
    }

    private static String clean(String type, String value) {
        String v = value.trim();
        if (type.equals("TXT")) {
            // Multi-string TXT records arrive as "part1" "part2"
            v = v.replaceAll("\"\\s+\"", "");
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
        } else if (v.endsWith(".")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static DirContext context() throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "3000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        return new InitialDirContext(env);
    }
}
