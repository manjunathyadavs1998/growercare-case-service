package com.growercare.casesvc.domain.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.growercare.casesvc.support.TestImages;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SPEC-CASE-05 D6 / R106: location and text metadata never reaches the clean bucket. */
class ImageMetadataStripperTest {

    @Test
    void caseE111JpegLosesExifAndIptcButStillDecodes() throws IOException {
        var original = TestImages.jpegWithGps();
        assertThat(TestImages.contains(original, "GPSLatitude")).isTrue();

        var stripped = ImageMetadataStripper.strip("image/jpeg", original);

        assertThat(TestImages.contains(stripped, "GPSLatitude")).isFalse();
        assertThat(TestImages.contains(stripped, "Photoshop 3.0")).isFalse();
        assertThat(TestImages.indexOf(stripped, new byte[] {(byte) 0xFF, (byte) 0xE1})).isEqualTo(-1);
        assertThat(ImageIO.read(new ByteArrayInputStream(stripped)).getWidth()).isEqualTo(16);
    }

    @Test
    void caseE112PngLosesTextChunksAndKeepsImageData() throws IOException {
        var original = TestImages.pngWithText();

        var stripped = ImageMetadataStripper.strip("image/png", original);

        assertThat(TestImages.contains(stripped, "tEXt")).isFalse();
        assertThat(TestImages.contains(stripped, "eXIf")).isFalse();
        int idat = TestImages.indexOf(original, "IDAT".getBytes());
        int strippedIdat = TestImages.indexOf(stripped, "IDAT".getBytes());
        assertThat(Arrays.copyOfRange(stripped, strippedIdat, stripped.length))
                .isEqualTo(Arrays.copyOfRange(original, idat, original.length));                       // IDAT..IEND unchanged
        assertThat(ImageIO.read(new ByteArrayInputStream(stripped)).getHeight()).isEqualTo(16);
    }

    @ParameterizedTest(name = "R105 corrupt {0} is rejected")
    @ValueSource(strings = {"image/jpeg", "image/png"})
    void corruptImagesAreRejected(String contentType) {
        var corrupt = "image/jpeg".equals(contentType)
                ? new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F}          // segment length beyond the end
                : new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0x7F, 0x7F};
        assertThatThrownBy(() -> ImageMetadataStripper.strip(contentType, corrupt)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherTypesPassThroughUnchanged() {
        var pdf = TestImages.pdf();
        assertThat(ImageMetadataStripper.strip("application/pdf", pdf)).isSameAs(pdf);
    }
}
