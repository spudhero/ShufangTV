package io.github.spudhero.shufangtv.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class AutoPagePolicyTest {
  @Test
  public void closingOneInterruptionCannotClearOtherReasonsToPause() {
    assertFalse(AutoPagePolicy.mayAdvance(true, false, true, 2, false, false));
    assertFalse(AutoPagePolicy.mayAdvance(true, false, true, 1, false, false));
    assertTrue(AutoPagePolicy.mayAdvance(true, false, true, 0, false, false));
    assertFalse(AutoPagePolicy.mayAdvance(true, true, true, 0, false, false));
    assertFalse(AutoPagePolicy.mayAdvance(true, false, false, 0, false, false));
    assertFalse(AutoPagePolicy.mayAdvance(true, false, true, 0, true, false));
    assertFalse(AutoPagePolicy.mayAdvance(true, false, true, 0, false, true));
    assertFalse(AutoPagePolicy.mayAdvance(false, false, true, 0, false, false));
  }
}
