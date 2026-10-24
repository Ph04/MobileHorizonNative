package dev.mobilehorizon.fabric.loader;

/** Main-class marker required by the GameProvider; this does not start Minecraft. */
public final class HorizonPreflightMain {
    private HorizonPreflightMain() {}
    public static void main(String[] args) {
        System.out.println("Horizon Fabric metadata preflight provider; no Minecraft Java client is present.");
    }
}
