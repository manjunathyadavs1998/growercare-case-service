package com.growercare.casesvc.domain.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.casesvc.support.TestImages;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-CASE-05 D5 / R104: the local scanner flags the EICAR test file anywhere in the stream. */
class EicarScannerTest {

    static Stream<Arguments> files() {
        var eicar = TestImages.EICAR.getBytes(StandardCharsets.US_ASCII);
        var big = new byte[3 * EicarScanner.CHUNK];                    // signature straddles a chunk boundary
        System.arraycopy(eicar, 0, big, EicarScanner.CHUNK - 20, eicar.length);
        return Stream.of(
                argumentSet("CASE-E113 eicar file", eicar, MalwareScanner.Verdict.INFECTED),
                argumentSet("eicar across a chunk boundary", big, MalwareScanner.Verdict.INFECTED),
                argumentSet("clean jpeg", TestImages.jpegWithGps(), MalwareScanner.Verdict.CLEAN),
                argumentSet("almost eicar", Arrays.copyOf(eicar, eicar.length - 1), MalwareScanner.Verdict.CLEAN));
    }

    @ParameterizedTest
    @MethodSource("files")
    void scan(byte[] content, MalwareScanner.Verdict expected) throws IOException {
        assertThat(new EicarScanner().scan(new ByteArrayInputStream(content))).isEqualTo(expected);
    }
}
