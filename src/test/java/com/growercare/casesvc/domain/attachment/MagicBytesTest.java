package com.growercare.casesvc.domain.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SPEC-CASE-05 R105: the stored bytes must be what the uploader declared. */
class MagicBytesTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(textBlock = """
            # name,                         content type,     first bytes (hex),                 matches
            jpeg is jpeg,                   image/jpeg,       ffd8ffe000104a464946,              true
            png is png,                     image/png,        89504e470d0a1a0a0000000d49484452,  true
            pdf is pdf,                     application/pdf,  255044462d312e37,                  true
            mp4 has ftyp at 4,              video/mp4,        0000002066747970,                  true
            CASE-E114 pdf declared as png,  image/png,        255044462d312e37,                  false
            exe declared as jpeg,           image/jpeg,       4d5a90000300,                      false
            too short,                      image/png,        8950,                              false
            unknown type,                   text/html,        3c68746d6c3e,                      false
            """)
    void matches(String name, String contentType, String hex, boolean expected) {
        assertThat(MagicBytes.matches(contentType, HexFormat.of().parseHex(hex))).isEqualTo(expected);
    }
}
