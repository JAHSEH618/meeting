package com.meeting.api.client.common;

import java.util.List;

public record PageResult<T>(
    List<T> items,
    PageInfo page
) {
    public record PageInfo(
        String cursor,
        boolean hasMore,
        int limit
    ) {
    }
}
