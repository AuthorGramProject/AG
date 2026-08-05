#!/usr/bin/env python3
"""Constrain chat context menus to the actual visible work area."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
MARKER = "AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS"

text = PATH.read_text(encoding="utf-8")
if MARKER not in text:
    old = """        int constrainedHeightSpec = maxHeight != 0 ? MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST) : heightMeasureSpec;
        int adjustedWidthSpec = widthMeasureSpec;
        super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);
"""
    new = """        // AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS
        // Some OEM/window combinations pass an effectively unbounded measure spec.
        // Always cap the menu to the real display/work-area height so the internal
        // ScrollView scrolls instead of the popup escaping below the screen.
        int parentMode = MeasureSpec.getMode(heightMeasureSpec);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
        int displayHeight = Math.max(AndroidUtilities.dp(240), AndroidUtilities.displaySize.y);
        int availableHeight = parentMode == MeasureSpec.UNSPECIFIED || parentHeight <= 0
                ? displayHeight
                : Math.min(parentHeight, displayHeight);
        availableHeight = Math.max(AndroidUtilities.dp(160), availableHeight - AndroidUtilities.dp(16));
        int effectiveMaxHeight = maxHeight > 0
                ? Math.min(maxHeight, availableHeight)
                : availableHeight;
        int constrainedHeightSpec = MeasureSpec.makeMeasureSpec(effectiveMaxHeight, MeasureSpec.AT_MOST);
        int adjustedWidthSpec = widthMeasureSpec;
        super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);
"""
    if text.count(old) != 1:
        raise SystemExit(f"popup measure anchor count is {text.count(old)}, expected 1")
    text = text.replace(old, new, 1)

    old_setter = """    public void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
    }
"""
    new_setter = """    public void setMaxHeight(int maxHeight) {
        int safeDisplayHeight = Math.max(AndroidUtilities.dp(160), AndroidUtilities.displaySize.y - AndroidUtilities.dp(16));
        this.maxHeight = maxHeight > 0 ? Math.min(maxHeight, safeDisplayHeight) : safeDisplayHeight;
        requestLayout();
    }
"""
    if text.count(old_setter) != 1:
        raise SystemExit(f"popup max-height setter anchor count is {text.count(old_setter)}, expected 1")
    text = text.replace(old_setter, new_setter, 1)
    PATH.write_text(text, encoding="utf-8")

check = PATH.read_text(encoding="utf-8")
required = (
    MARKER,
    "effectiveMaxHeight",
    "requestLayout();",
)
missing = [item for item in required if item not in check]
if missing:
    raise SystemExit(f"adaptive popup bounds validation failed: {missing}")
print("Adaptive chat popup bounds patch passed")
