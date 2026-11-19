package com.jistack.platform.common.web;

import java.util.List;

public record PageResult<T>(List<T> records, long total, long page, long size) {
}
