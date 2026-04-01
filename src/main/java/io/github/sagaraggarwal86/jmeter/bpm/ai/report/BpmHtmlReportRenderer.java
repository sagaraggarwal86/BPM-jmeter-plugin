package io.github.sagaraggarwal86.jmeter.bpm.ai.report;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders AI-generated Markdown into a styled standalone HTML report
 * with a sidebar navigation derived from H2 headings.
 */
public final class BpmHtmlReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(BpmHtmlReportRenderer.class);
    private static final Pattern H2_PATTERN = Pattern.compile("<h2>(.*?)</h2>");

    private BpmHtmlReportRenderer() { }

    /**
     * Converts Markdown to a complete standalone HTML page with sidebar navigation.
     *
     * @param markdown       AI-generated Markdown text
     * @param providerName   display name of the AI provider used
     * @return complete HTML string ready to write to a file
     */
    public static String render(String markdown, String providerName) {
        // Parse Markdown to HTML
        Parser parser = Parser.builder().build();
        Node document = parser.parse(markdown);
        HtmlRenderer renderer = HtmlRenderer.builder().build();
        String htmlContent = renderer.render(document);

        // Extract H2 headings for sidebar navigation
        List<String> headings = extractH2Headings(htmlContent);

        // Add IDs to H2 tags for anchor links
        htmlContent = addHeadingIds(htmlContent, headings);

        // Build complete HTML page
        return buildHtmlPage(htmlContent, headings, providerName);
    }

    private static List<String> extractH2Headings(String html) {
        List<String> headings = new ArrayList<>();
        Matcher m = H2_PATTERN.matcher(html);
        while (m.find()) {
            headings.add(m.group(1));
        }
        return headings;
    }

    private static String addHeadingIds(String html, List<String> headings) {
        for (String heading : headings) {
            String id = heading.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            html = html.replace("<h2>" + heading + "</h2>",
                    "<h2 id=\"" + id + "\">" + heading + "</h2>");
        }
        return html;
    }

    private static String buildHtmlPage(String content, List<String> headings, String providerName) {
        StringBuilder nav = new StringBuilder();
        for (String heading : headings) {
            String id = heading.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            nav.append("      <a href=\"#").append(id).append("\">").append(heading).append("</a>\n");
        }

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>BPM AI Performance Report</title>
                  <style>
                    :root { --sidebar-width: 220px; --brand-color: #2563eb; --bg: #f8fafc; }
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                           background: var(--bg); color: #1e293b; line-height: 1.6; }
                    .header { background: var(--brand-color); color: white; padding: 20px 30px;
                              position: fixed; top: 0; left: 0; right: 0; z-index: 100; }
                    .header h1 { font-size: 1.3em; font-weight: 600; }
                    .header .subtitle { font-size: 0.85em; opacity: 0.85; margin-top: 2px; }
                    .sidebar { position: fixed; top: 70px; left: 0; width: var(--sidebar-width);
                               height: calc(100vh - 70px); overflow-y: auto; background: white;
                               border-right: 1px solid #e2e8f0; padding: 16px 0; }
                    .sidebar a { display: block; padding: 8px 20px; color: #475569; text-decoration: none;
                                 font-size: 0.9em; border-left: 3px solid transparent; }
                    .sidebar a:hover { background: #f1f5f9; color: var(--brand-color);
                                       border-left-color: var(--brand-color); }
                    .content { margin-left: var(--sidebar-width); margin-top: 70px; padding: 30px 40px;
                               max-width: 900px; }
                    h2 { color: var(--brand-color); border-bottom: 2px solid #e2e8f0;
                         padding-bottom: 8px; margin: 30px 0 16px; font-size: 1.4em; }
                    h3 { color: #334155; margin: 20px 0 10px; font-size: 1.1em; }
                    p { margin-bottom: 12px; }
                    ul, ol { margin: 0 0 12px 20px; }
                    li { margin-bottom: 4px; }
                    table { border-collapse: collapse; width: 100%%; margin: 16px 0; }
                    th, td { border: 1px solid #e2e8f0; padding: 8px 12px; text-align: left; }
                    th { background: #f1f5f9; font-weight: 600; }
                    tr:nth-child(even) { background: #f8fafc; }
                    blockquote { border-left: 4px solid #f59e0b; background: #fffbeb;
                                 padding: 12px 16px; margin: 16px 0; border-radius: 4px; }
                    blockquote strong { color: #92400e; }
                    code { background: #f1f5f9; padding: 2px 6px; border-radius: 3px;
                           font-size: 0.9em; }
                    @media (max-width: 768px) {
                      .sidebar { display: none; }
                      .content { margin-left: 0; padding: 20px; }
                    }
                  </style>
                </head>
                <body>
                  <div class="header">
                    <h1>BPM Browser Performance Analysis</h1>
                    <div class="subtitle">Generated by %s | Browser Performance Metrics Plugin</div>
                  </div>
                  <nav class="sidebar">
                %s  </nav>
                  <main class="content">
                %s
                  </main>
                  <script>
                    document.querySelectorAll('.sidebar a').forEach(link => {
                      link.addEventListener('click', e => {
                        e.preventDefault();
                        document.querySelector(link.getAttribute('href')).scrollIntoView({behavior:'smooth'});
                      });
                    });
                  </script>
                </body>
                </html>
                """.formatted(providerName, nav.toString(), content);
    }
}
