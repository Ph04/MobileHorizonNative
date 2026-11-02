# WIT Mod Menu port seed

This is a **derived WIT port slice** of the original `mod_menu-0.3.0.hnmod`.
The original source package is not modified.

The port uses Horizon's own settings-page widgets and the new experimental
`mod-manager` capability. It lists installed WIT/content modules and toggles
non-development entries. The host applies the change after the current hook
returns and reloads the module set.

This first slice intentionally does not reproduce every desktop Mod Menu
feature (search text entry, sorting, badge artwork, native mixin inventory,
or every diagnostic action). Those need additional supported UI widgets or
host information. It is a starting point for testing direct WIT ↔ Horizon
communication, not a claim of full feature parity.

The module requests `ui` and `mod-manager`; both must be declared in
`capabilities` and `experimental`. The game checks component imports.
