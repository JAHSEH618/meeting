package com.meeting.api;

import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.domain.meeting.Meeting;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;

/**
 * Builds {@link Meeting} instances for tests via reflection — the
 * production Builder is package-private, so we go through the public
 * factory and then patch the version columns. Kept in a separate file
 * so it can be reused by other test classes (e.g. export, RAG).
 */
final class MeetingTestFactory {

    private MeetingTestFactory() {}

    static Meeting create(
        String meetingId, String tenantId,
        int transcriptVersion, int minutesVersion
    ) {
        Meeting base = Meeting.create(
            meetingId, tenantId, "test meeting " + meetingId,
            "zh",
            java.util.List.of(),
            "user_test"
        );
        try {
            return rebuildWithVersions(base, transcriptVersion, minutesVersion);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Cannot rebuild Meeting with versions", ex);
        }
    }

    private static Meeting rebuildWithVersions(
        Meeting source, int transcriptVersion, int minutesVersion
    ) throws ReflectiveOperationException {
        // Walk into Meeting's private Builder by reflection.
        Class<?> builderClass = Class.forName("com.meeting.api.domain.meeting.Meeting$Builder");
        Constructor<?> ctor = builderClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object builder = ctor.newInstance();
        // Set every field from source via accessor calls on the Builder
        setBuilder(builder, "id", source.id());
        setBuilder(builder, "tenantId", source.tenantId());
        setBuilder(builder, "title", source.title());
        setBuilder(builder, "securityLevel", source.securityLevel());
        setBuilder(builder, "status", MeetingStatus.CREATED);
        setBuilder(builder, "language", source.language());
        setBuilder(builder, "transcriptVersion", transcriptVersion);
        setBuilder(builder, "minutesVersion", minutesVersion);
        setBuilder(builder, "createdAt", source.createdAt());
        setBuilder(builder, "createdBy", source.createdBy());
        setBuilder(builder, "participants", source.participants());
        var build = builderClass.getDeclaredMethod("build");
        build.setAccessible(true);
        return (Meeting) build.invoke(builder);
    }

    private static void setBuilder(Object builder, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field f = builder.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(builder, value);
        // ignore unused imports for OffsetDateTime — used implicitly via reflection
        if (false) { OffsetDateTime.now(); }
    }
}
