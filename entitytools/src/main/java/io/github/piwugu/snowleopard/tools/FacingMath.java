package io.github.piwugu.snowleopard.tools;

/**
 * 朝向数学（M2，2026-10-23 12:50 (UTC+8)）——纯 Java；M3 交互的 facing 组件共用同一出处。
 *
 * <p>MC 角度约定：yaw 0=+z（南）、90=-x（西）、-90=+x（东）；pitch 负=抬头。
 * 前向向量 = (-sin yaw·cos pitch, -sin pitch, cos yaw·cos pitch)。
 */
public final class FacingMath {

    /** 角度（度）。 */
    public record Angle(double yaw, double pitch) {
    }

    private FacingMath() {
    }

    /** 从 (fromX,fromY,fromZ) 看向 (toX,toY,toZ) 的 yaw/pitch（度）。 */
    public static Angle aim(double fromX, double fromY, double fromZ,
                            double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, horiz));
        return new Angle(yaw, pitch);
    }

    /** 两角之差的绝对值（度，处理 ±180 环绕）。 */
    public static double deltaDeg(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }
}
