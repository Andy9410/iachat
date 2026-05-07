package com.academy.chatservice.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Converter
public class VectorConverter implements AttributeConverter<List<Float>, String> {

    @Override
    public String convertToDatabaseColumn(List<Float> vector) {
        if (vector == null) return null;
        return vector.stream().map(Object::toString).collect(Collectors.joining(",", "[", "]"));
    }

    @Override
    public List<Float> convertToEntityAttribute(String value) {
        if (value == null) return null;
        String stripped = value.replaceAll("[\\[\\]]", "");
        return Arrays.stream(stripped.split(","))
                .map(Float::parseFloat)
                .collect(Collectors.toList());
    }
}
