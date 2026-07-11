package com.example.mlv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathUtilTest {

    @Test
    void sourceListLabelReturnsParentFolderAndFileName() {
        assertEquals("samples\\mybatis-sample.log",
                PathUtil.sourceListLabel("D:\\workspace\\MyBatis-log-viewer\\samples\\mybatis-sample.log"));
        assertEquals("samples/mybatis-sample.log",
                PathUtil.sourceListLabel("/var/log/samples/mybatis-sample.log"));
    }

    @Test
    void sourceListLabelHandlesSingleSegment() {
        assertEquals("mybatis-sample.log", PathUtil.sourceListLabel("mybatis-sample.log"));
        assertEquals("-", PathUtil.sourceListLabel(null));
        assertEquals("-", PathUtil.sourceListLabel(""));
    }
}
