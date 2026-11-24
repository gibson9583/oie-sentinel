/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ${name}} substitution for the email subject and webhook templates.
 * One pass over the template: a substituted value is never scanned again, so
 * a logged line containing {@code ${valueJson}} reaches the output as typed.
 */
final class TemplateTokens {

    private static final Pattern TOKEN = Pattern.compile("\\$\\{(\\w+)}");

    private TemplateTokens() {
    }

    /** Unknown tokens stay verbatim, so a typo shows in the output instead of costing the notification. */
    static String render(String template, Map<String, String> values) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
