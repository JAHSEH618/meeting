package com.meeting.api.infrastructure.gateway.export;

import com.meeting.api.client.enums.ExportFormat;
import com.meeting.api.domain.export.ExportGateway;
import com.meeting.api.domain.export.ExportInputInvalidException;
import com.meeting.api.client.common.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Spring-managed registry that routes a render call by
 * {@link ExportFormat} to the right {@link ExportGateway}
 * implementation. Throws {@link ExportInputInvalidException} for
 * formats that have no registered gateway — the queue consumer will
 * mark the job FAILED without retry.
 */
@Component
public class ExportGatewayRegistry {

    private final Map<ExportFormat, ExportGateway> byFormat;

    public ExportGatewayRegistry(List<ExportGateway> gateways) {
        java.util.Map<ExportFormat, ExportGateway> map = new java.util.EnumMap<>(ExportFormat.class);
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
        this.byFormat = java.util.Collections.unmodifiableMap(map);
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
