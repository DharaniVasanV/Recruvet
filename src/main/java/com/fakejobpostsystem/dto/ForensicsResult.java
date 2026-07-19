package com.fakejobpostsystem.dto;

import java.util.List;

public record ForensicsResult(
        double forensicsScore,
        List<RedFlagCheck> flags
) {
}
