package com.fakejobpostsystem.dto;

import java.util.List;

public record Entities(List<String> phones, List<String> emails, List<String> persons, List<String> organizations) {
}
