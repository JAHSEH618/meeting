package com.meeting.api.domain.export;

import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ExportFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Routes a render call by {@link ExportFormat} to the right
 * {@link ExportGateway} implementation. Throws
 * {@link ExportInputInvalidException} for formats that have no registered
 * gateway — the queue consumer will mark the job FAILED without retry.
 *
 * <p>Pure domain POJO; framework-light by design. Infrastructure wires
 * a Spring-managed bean by passing in the list of @Component gateways
 * via a configuration class.
 */
public final class ExportGatewayRegistry {

    private final Map<ExportFormat, ExportGateway> byFormat;

    public ExportGatewayRegistry(Collection<ExportGateway> gateways) {
        Map<ExportFormat, ExportGateway> map = new EnumMap<>(ExportFormat.class);
        for (ExportGateway gw : gateways) {
            ExportFormat fmt = gw.supportedFormat();
            ExportGateway existing = map.put(fmt, gw);
            if (existing != null) {
                throw new IllegalStateException(
                    "Multiple ExportGateway beans for format " + fmt
                        + ": " + existing.getClass().getName()
                        + " and " + gw.getClass().getName()
                );
            }
        }
        this.byFormat = Collections.unmodifiableMap(map);
    }

    public ExportGateway gateway(ExportFormat format) {
        ExportGateway gw = byFormat.get(format);
        if (gw == null) {
            throw new ExportInputInvalidException(
                ErrorCode.EXPORT_FORMAT_UNSUPPORTED,
                "no ExportGateway registered for format " + format
            );
        }
        return gw;
    }
}
