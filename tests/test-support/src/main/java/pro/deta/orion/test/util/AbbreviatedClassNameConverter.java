package pro.deta.orion.test.util;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public class AbbreviatedClassNameConverter extends ClassicConverter {
    private static final int SHORTEN_PACKAGES = 0;
    @Override
    public String convert(ILoggingEvent event) {
        String fqcn = event.getLoggerName(); // full class name
        String[] parts = fqcn.split("\\.");
        if (parts.length == 0) return fqcn;
        StringBuilder sb = new StringBuilder();
        int package_to = parts.length - 1;
        int package_start
                = SHORTEN_PACKAGES > 0 ? Math.max(0, package_to - SHORTEN_PACKAGES) : 0;
        for (int i = package_start; i < package_to; i++) {
            sb.append(parts[i].charAt(0)).append('.');
        }
        sb.append(parts[package_to]); // append class name
        return sb.toString();
    }
}
