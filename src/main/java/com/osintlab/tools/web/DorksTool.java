package com.osintlab.tools.web;

import com.osintlab.support.Inputs;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ready-made search-engine queries ("dorks") for the target, as Google/Bing/DuckDuckGo links. */
@Component
public class DorksTool implements OsintTool {

    @Override
    public String id() {
        return "dorks";
    }

    @Override
    public Object run(String target) {
        String t = Inputs.require(target, "target");
        List<String[]> dorks = new ArrayList<>();

        if (t.contains("@") && !t.startsWith("@")) {
            String email = Inputs.email(t);
            dorks.add(new String[]{"Exact mentions", "\"" + email + "\""});
            dorks.add(new String[]{"In documents", "\"" + email + "\" (filetype:pdf OR filetype:doc OR filetype:docx OR filetype:xls OR filetype:xlsx)"});
            dorks.add(new String[]{"On paste sites", "\"" + email + "\" (site:pastebin.com OR site:ghostbin.site OR site:rentry.co)"});
            dorks.add(new String[]{"On GitHub", "site:github.com \"" + email + "\""});
            dorks.add(new String[]{"Social media", "\"" + email + "\" (site:linkedin.com OR site:facebook.com OR site:x.com OR site:instagram.com)"});
        } else if (t.matches("^\\+?[0-9 ().\\-/]+$")) {
            String phone = Inputs.phone(t);
            String national = phone.startsWith("+") ? phone.substring(1) : phone;
            dorks.add(new String[]{"Exact number", "\"" + phone + "\" OR \"" + national + "\""});
            dorks.add(new String[]{"Social media", "\"" + phone + "\" (site:facebook.com OR site:linkedin.com OR site:instagram.com)"});
            dorks.add(new String[]{"In documents", "\"" + phone + "\" (filetype:pdf OR filetype:doc OR filetype:xls)"});
        } else if (t.contains(".") && !t.contains(" ")) {
            String d = Inputs.domain(t);
            dorks.add(new String[]{"All indexed pages", "site:" + d});
            dorks.add(new String[]{"Subdomains", "site:*." + d + " -site:www." + d});
            dorks.add(new String[]{"Documents", "site:" + d + " (filetype:pdf OR filetype:doc OR filetype:docx OR filetype:ppt OR filetype:pptx)"});
            dorks.add(new String[]{"Spreadsheets / data", "site:" + d + " (filetype:xls OR filetype:xlsx OR filetype:csv OR filetype:json)"});
            dorks.add(new String[]{"Login / admin pages", "site:" + d + " (inurl:login OR inurl:admin OR inurl:signin OR intitle:login)"});
            dorks.add(new String[]{"Directory listings", "site:" + d + " intitle:\"index of\""});
            dorks.add(new String[]{"Config / log files", "site:" + d + " (ext:env OR ext:log OR ext:sql OR ext:bak OR ext:conf OR ext:ini)"});
            dorks.add(new String[]{"Email addresses", "\"@" + d + "\" -site:" + d});
            dorks.add(new String[]{"Mentions elsewhere", "\"" + d + "\" -site:" + d});
            dorks.add(new String[]{"Code on GitHub", "site:github.com \"" + d + "\""});
            dorks.add(new String[]{"Paste sites", "\"" + d + "\" (site:pastebin.com OR site:rentry.co)"});
        } else if (t.contains(" ")) {
            String c = Inputs.company(t);
            dorks.add(new String[]{"Exact name", "\"" + c + "\""});
            dorks.add(new String[]{"Employees on LinkedIn", "site:linkedin.com/in \"" + c + "\""});
            dorks.add(new String[]{"Company documents", "\"" + c + "\" (filetype:pdf OR filetype:docx OR filetype:pptx)"});
            dorks.add(new String[]{"Company registers", "\"" + c + "\" (site:northdata.com OR site:opencorporates.com OR site:handelsregister.de)"});
            dorks.add(new String[]{"News", "\"" + c + "\" (site:reuters.com OR site:handelsblatt.com OR site:techcrunch.com)"});
        } else {
            String u = Inputs.username(t);
            dorks.add(new String[]{"Exact username", "\"" + u + "\""});
            dorks.add(new String[]{"Username in URLs", "inurl:" + u});
            dorks.add(new String[]{"Social media", "\"" + u + "\" (site:facebook.com OR site:instagram.com OR site:x.com OR site:tiktok.com OR site:reddit.com)"});
            dorks.add(new String[]{"Forums", "\"" + u + "\" (inurl:forum OR inurl:member OR inurl:profile OR inurl:user)"});
            dorks.add(new String[]{"Paste sites", "\"" + u + "\" (site:pastebin.com OR site:rentry.co)"});
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (String[] d : dorks) {
            String q = URLEncoder.encode(d[1], StandardCharsets.UTF_8);
            Map<String, String> row = new LinkedHashMap<>();
            row.put("purpose", d[0]);
            row.put("query", d[1]);
            row.put("google", "https://www.google.com/search?q=" + q);
            row.put("bing", "https://www.bing.com/search?q=" + q);
            row.put("duckduckgo", "https://duckduckgo.com/?q=" + q);
            rows.add(row);
        }
        return Map.of("dorks", rows);
    }
}
