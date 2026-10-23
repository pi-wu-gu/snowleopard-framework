package io.github.piwugu.snowleopard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 朝向数学（M2）——MC 角度约定的锚定测试。
 */
class FacingMathTest {

    @Test
    void cardinalDirections() {
        // 站原点看 +z（南）→ yaw 0
        assertEquals(0.0, FacingMath.aim(0, 0, 0, 0, 0, 5).yaw(), 1e-6);
        // 看 +x（东）→ yaw -90
        assertEquals(-90.0, FacingMath.aim(0, 0, 0, 5, 0, 0).yaw(), 1e-6);
        // 看 -x（西）→ yaw 90
        assertEquals(90.0, FacingMath.aim(0, 0, 0, -5, 0, 0).yaw(), 1e-6);
        // 看 -z（北）→ yaw ±180
        assertEquals(180.0, Math.abs(FacingMath.aim(0, 0, 0, 0, 0, -5).yaw()), 1e-6);
    }

    @Test
    void pitchUpDown() {
        assertEquals(-90.0, FacingMath.aim(0, 0, 0, 0, 10, 0).pitch(), 1e-6, "抬头 pitch=-90");
        assertEquals(90.0, FacingMath.aim(0, 0, 0, 0, -10, 0).pitch(), 1e-6, "低头 pitch=+90");
        assertEquals(0.0, FacingMath.aim(0, 0, 0, 5, 0, 0).pitch(), 1e-6);
    }

    @Test
    void deltaWraps() {
        assertEquals(10.0, FacingMath.deltaDeg(175, -175), 1e-6, "±180 环绕");
        assertEquals(0.0, FacingMath.deltaDeg(30, 30), 1e-6);
        assertTrue(FacingMath.deltaDeg(0, 90) == 90.0);
    }
}
