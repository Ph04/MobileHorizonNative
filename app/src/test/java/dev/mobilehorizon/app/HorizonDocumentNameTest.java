package dev.mobilehorizon.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class HorizonDocumentNameTest {
    @Test public void recoversFilenameWhenProviderDisplayNameIsNull() {
        assertEquals("minimap.hnmod", HorizonDocumentName.resolve(
                null, "primary:Download/minimap.hnmod", "document/42", ".hnmod"));
    }

    @Test public void prefersDocumentIdWhenProviderReturnsOpaqueLabel() {
        assertEquals("zoom.HNMOD", HorizonDocumentName.resolve(
                "document", "primary:Download/zoom.HNMOD", "document/42", ".hnmod"));
    }

    @Test public void prefersProviderFilenameWhenItHasExpectedExtension() {
        assertEquals("veinminer.hnmod", HorizonDocumentName.resolve(
                "veinminer.hnmod", "primary:Download/other.hnmod", "document/42", ".hnmod"));
    }

    @Test public void acceptsExtensionCaseInsensitively() {
        assertEquals("native.HNMOD", HorizonDocumentName.resolve(
                "native.HNMOD", null, null, ".hnmod"));
    }

    @Test public void returnsBestAvailableNameForUsefulValidationError() {
        assertEquals("download-item", HorizonDocumentName.resolve(
                "  download-item  ", null, null, ".hnmod"));
    }

    @Test public void validatesHnmodByContentsWhenSafDoesNotExposeItsName() {
        assertEquals(false, HorizonDocumentName.requiresFilenameExtension(".hnmod"));
        assertEquals(false, HorizonDocumentName.requiresFilenameExtension(".HNMOD"));
    }

    @Test public void stillRequiresRuntimeBinaryExtension() {
        assertEquals(true, HorizonDocumentName.requiresFilenameExtension(".so"));
    }

    @Test public void returnsEmptyWhenProviderSuppliesNoName() {
        assertEquals("", HorizonDocumentName.resolve(null, null, "  ", ".hnmod"));
    }

    @Test public void stripsPathFromProviderAndDocumentNames() {
        assertEquals("module.hnmod", HorizonDocumentName.resolve(
                "Downloads/module.hnmod", null, null, ".hnmod"));
    }
}
