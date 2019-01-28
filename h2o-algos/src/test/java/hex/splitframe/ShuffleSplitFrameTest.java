package hex.splitframe;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import water.Key;
import water.MRTask;
import water.TestUtil;
import water.fvec.*;
import water.parser.BufferedString;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static water.fvec.FrameTestUtil.createFrame;
import static water.fvec.FrameTestUtil.collectS;
import static water.util.ArrayUtils.flat;
import static water.util.ArrayUtils.append;

/**
 * Tests for shuffle split frame.
 */
public class ShuffleSplitFrameTest extends TestUtil {

  @BeforeClass() public static void setup() { stall_till_cloudsize(1); }

  /** Reported as PUBDEV-452 */
  @Test
  public void testShuffleSplitOnStringColumn() {
    long[] chunkLayout = ar(2L, 2L, 3L);
    String[][] data = ar(ar("A", "B"), ar(null, "C"), ar("D", "E", "F"));
    Frame f = createFrame("ShuffleSplitTest1.hex", chunkLayout, data);
    testScenario(f, flat(data));

    chunkLayout = ar(3L, 3L);
    data = ar(ar("A", null, "B"), ar("C", "D", "E"));
    f = createFrame("test2.hex", chunkLayout, data);
    testScenario(f, flat(data));
  }

  @Test /* this test makes sure that the rows of the split frames are preserved (including UUID) */
  public void testShuffleSplitWithMultipleColumns() {
    long[] chunkLayout = ar(2L, 2L, 3L);
    String[][] data = ar(ar("1", "2"), ar(null, "3"), ar("4", "5", "6"));
    Frame f = null;
    Frame tmpFrm = createFrame("ShuffleSplitMCTest1.hex", chunkLayout, data);
    try {
      f = new MRTask() {
        @Override
        public void map(Chunk[] cs, NewChunk[] ncs) {
          for (int i = 0; i < cs[0]._len; i++) {
            BufferedString bs = cs[0].atStr(new BufferedString(), i);
            int val = bs == null ? 0 : Integer.parseInt(bs.toString());
            ncs[0].addStr(bs);
            ncs[1].addNum(val);
            ncs[2].addNum(i);
            ncs[3].addUUID(i, val);
          }
        }
      }.doAll(new byte[]{Vec.T_STR, Vec.T_NUM, Vec.T_NUM, Vec.T_UUID}, tmpFrm).outputFrame();
    } finally {
      tmpFrm.delete();
    }
    testScenario(f, flat(data), new MRTask() {
      @Override
      public void map(Chunk[] cs) {
        for (int i = 0; i < cs[0]._len; i++) {
          BufferedString bs = cs[0].atStr(new BufferedString(), i);
          int expectedVal = bs == null ? 0 : Integer.parseInt(bs.toString());
          int expectedIndex = (int) cs[2].atd(i);
          Assert.assertEquals((double) expectedVal, cs[1].atd(i), 0.00001);
          Assert.assertEquals(expectedIndex, (int) cs[3].at16l(i));
          Assert.assertEquals(expectedVal, (int) cs[3].at16h(i));
        }
      }
    });
  }

  static void testScenario(Frame f, String[] expValues) { testScenario(f, expValues, null); }

  /** Simple testing scenario, splitting frame in the middle and comparing the values */
  static void testScenario(Frame f, String[] expValues, MRTask chunkAssertions) {
    double[] ratios = ard(0.5, 0.5);
    Key<Frame>[] keys = aro(Key.<Frame>make("test.hex"), Key.<Frame>make("train.hex"));
    Frame[] splits = null;
    try {
      splits = ShuffleSplitFrame.shuffleSplitFrame(f, keys, ratios, 42);
      Assert.assertEquals("Expecting 2 splits", 2, splits.length);
      // Collect values from both splits
      String[] values = append(
              collectS(splits[0].vec(0)),
              collectS(splits[1].vec(0)));
      // Sort values, but first replace all nulls by unique value
      Arrays.sort(replaceNulls(expValues));
      Arrays.sort(replaceNulls(values));
      Assert.assertArrayEquals("Values should match", expValues, values);
      if (chunkAssertions != null) {
        for (Frame s: splits) chunkAssertions.doAll(s).getResult();
      }
    } finally {
      f.delete();
      if (splits!=null) for(Frame s: splits) s.delete();
    }
  }

  private static String[] replaceNulls(String[] ary) {
    return replaceNulls(ary, "_NA_#");
  }
  private static String[] replaceNulls(String[] ary, String replacement) {
    for (int i = 0; i < ary.length; i++ ) {
      if (ary[i] == null) ary[i] = replacement;
    }
    return ary;
  }


  @Test
  public void splitRatioIsWorkingCorrectly() {
    Frame leaderboardFrame = null;

    try {
      leaderboardFrame = new TestFrameBuilder()
              .withName("leaderboardFrame")
              .withColNames("ColA", "ColB", "ColC")
              .withVecTypes(Vec.T_CAT, Vec.T_CAT, Vec.T_CAT)
              .withDataForCol(0, ar("a", "b", "b", "b", "a", "a"))
              .withDataForCol(1, ar("yellow", "blue", "green", "red", "purple", "orange"))
              .withDataForCol(2, ar("2", "6", "6", "6", "6", "2"))
              .build();

      double[] splitRatio = {0.8, 0.2};
      Key[] keysForSplits = {Key.make("key_" + leaderboardFrame._key), Key.make("key2_" + leaderboardFrame._key)};
      Frame[] splits = ShuffleSplitFrame.shuffleSplitFrame(leaderboardFrame, keysForSplits, splitRatio, 1234);
      System.out.println("Number of row in original frame: " + leaderboardFrame.numRows());
      assertEquals(5, splits[0].numRows());
      assertEquals(1, splits[1].numRows());

    } finally {
      leaderboardFrame.delete();
    }
  }

  @Test
  public void splitRatioIsWorkingCorrectlyOnBigData() {
    Frame leaderboardFrame = null;

    try {
      leaderboardFrame = new TestFrameBuilder()
              .withName("leaderboardFrame")
              .withColNames("ColA")
              .withVecTypes(Vec.T_NUM)
              .withRandomDoubleDataForCol(0, 1000, 20, 90, 1234L)
              .build();

      double[] splitRatio = {0.8, 0.2};
      Key[] keysForSplits = {Key.make("key_" + leaderboardFrame._key), Key.make("key2_" + leaderboardFrame._key)};
      Frame[] splits = ShuffleSplitFrame.shuffleSplitFrame(leaderboardFrame, keysForSplits, splitRatio, 1234);
      System.out.println("Number of row in original frame: " + leaderboardFrame.numRows());
      assertEquals(800, splits[0].numRows(), 10);
      assertEquals(200, splits[1].numRows(), 10);

    } finally {
      leaderboardFrame.delete();
    }
  }

  @Test
  public void numberOfPartitionsIsMoreImportantThenCorrectRation() {
    Frame leaderboardFrame = null;

    try {
      leaderboardFrame = new TestFrameBuilder()
              .withName("leaderboardFrame")
              .withColNames("ColA", "ColB", "ColC")
              .withVecTypes(Vec.T_CAT, Vec.T_CAT, Vec.T_CAT)
              .withDataForCol(0, ar("a", "b"))
              .withDataForCol(1, ar("yellow", "blue"))
              .withDataForCol(2, ar("2", "6"))
              .build();

      double[] splitRatio = {0.9, 0.1};
      Key[] keysForSplits = {Key.make("key_" + leaderboardFrame._key), Key.make("key2_" + leaderboardFrame._key)};
      Frame[] splits = ShuffleSplitFrame.shuffleSplitFrame(leaderboardFrame, keysForSplits, splitRatio, 1234);
      System.out.println("Number of row in original frame: " + leaderboardFrame.numRows());
      assertEquals(1, splits[0].numRows());
      assertEquals(1, splits[1].numRows());

    } finally {
      leaderboardFrame.delete();
    }
  }

}

