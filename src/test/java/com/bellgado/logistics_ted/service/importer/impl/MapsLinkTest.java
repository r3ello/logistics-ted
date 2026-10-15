package com.bellgado.logistics_ted.service.importer.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The Google Maps links the client pastes into the staff sheet's Локация (2026-10-15 export). */
class MapsLinkTest {

    @Test
    void aPlaceLinkGivesItsNameAndPin() {
        MapsLink l = MapsLink.parse("https://www.google.com/maps/place/St.+Anna+University+Hospital/"
            + "@42.6605032,23.3733855,40m/data=!3m1!1e3!4m6!3m5!1s0x40aa8680dcb646e5:0xff1aeee506bb6901!8m2"
            + "!3d42.6605264!4d23.3733558!16s%2Fg%2F11f3nj4n30!5m1!1e4?entry=ttu&g_ep=EgoyMDI2MTAwNy4wIKXMDSoASAFQAw%3D%3D");

        assertThat(l.name()).isEqualTo("St. Anna University Hospital");
        assertThat(l.lat()).isEqualByComparingTo("42.660526");             // the pin, not the map centre
        assertThat(l.lng()).isEqualByComparingTo("23.373356");
    }

    @Test
    void aLinkFoundThroughASearchUsesThePlacesPinNotTheSearchedArea() {
        MapsLink l = MapsLink.parse("https://www.google.com/maps/place/Palace+of+Culture/@42.6057511,23.0268289,863m/"
            + "data=!3m2!1e3!5s0x14aacac198912931:0x1bd013f3114abd67!4m15!1m8!3m7!1s0x14aacac1b9dd0941:0xd8585f364e801b16"
            + "!2sTsentar,+Pernik!3b1!8m2!3d42.6037231!4d23.0294945!16s%2Fg%2F11fy4t4twx!3m5!1s0x14aacac17e53ba63:"
            + "0x58080e59a1741d74!8m2!3d42.6065748!4d23.0291286!16s%2Fg%2F1tfh1nh7?entry=ttu");

        assertThat(l.name()).isEqualTo("Palace of Culture");
        assertThat(l.lat()).isEqualByComparingTo("42.606575");
        assertThat(l.lng()).isEqualByComparingTo("23.029129");
    }

    @Test
    void withoutAPinTheMapCentreIsUsed() {
        MapsLink l = MapsLink.parse("https://www.google.com/maps/place/2757+Dolno+Osenovo/@41.95866,23.2206,14z");
        assertThat(l.name()).isEqualTo("2757 Dolno Osenovo");
        assertThat(l.lat()).isEqualByComparingTo(new BigDecimal("41.958660"));
    }

    @Test
    void aShortLinkIsRecognisedButHasNoCoordinates() {
        MapsLink l = MapsLink.parse("https://maps.app.goo.gl/oaBpZLZwu4dvmw4x6?g_st=av");
        assertThat(l).isNotNull();
        assertThat(l.hasCoordinates()).isFalse();
        assertThat(l.name()).isNull();
    }

    @Test
    void anythingElseIsNotALink() {
        assertThat(MapsLink.parse("София")).isNull();
        assertThat(MapsLink.parse("https://drive.google.com/file/d/x/view")).isNull();
        assertThat(MapsLink.parse(null)).isNull();
    }
}
