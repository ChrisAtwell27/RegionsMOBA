package com.regionsmoba.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BarTextTest {

    @Test
    void formatsTicksAsMinutesAndSeconds() {
        assertEquals("00:00", BarText.mmss(0));
        assertEquals("00:01", BarText.mmss(20));
        assertEquals("07:23", BarText.mmss(8860));
        assertEquals("15:00", BarText.mmss(18000));
    }

    @Test
    void negativeTicksClampToZero() {
        assertEquals("00:00", BarText.mmss(-40));
    }

    @Test
    void progressIsClampedToUnitRange() {
        assertEquals(0.0f, BarText.progress(0, 200));
        assertEquals(0.5f, BarText.progress(100, 200));
        assertEquals(1.0f, BarText.progress(300, 200));
        assertEquals(0.0f, BarText.progress(-5, 200));
    }

    @Test
    void progressIsZeroWhenMaxIsNonPositive() {
        assertEquals(0.0f, BarText.progress(50, 0));
    }

    @Test
    void furnaceBarReadsFullBeyondTheWindow() {
        assertEquals(1.0f, BarText.furnaceProgress(20000, 6000));
        assertEquals(1.0f, BarText.furnaceProgress(6000, 6000));
        assertEquals(0.5f, BarText.furnaceProgress(3000, 6000));
        assertEquals(0.0f, BarText.furnaceProgress(0, 6000));
    }
}
