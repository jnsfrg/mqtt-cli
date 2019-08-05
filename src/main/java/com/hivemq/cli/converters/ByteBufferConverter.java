package com.hivemq.cli.converters;

import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ByteBufferConverter implements CommandLine.ITypeConverter<ByteBuffer> {


    @Override
    public ByteBuffer convert(final @NotNull String s) throws Exception {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }
}