// 文件: app/src/test/java/com/melody/melodyplus/adapter/xiberia/XiberiaFeatureQueryPlanTest.kt
// 说明: 按型号能力位分发查询的断言（对齐官方 readState 过滤语义）。
package com.melody.melodyplus.adapter.xiberia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiberiaFeatureQueryPlanTest {

    @Test
    fun unknownProductId_emptyPlan() {
        assertTrue(XiberiaFeatureQueryPlan.planFor(0x999).isEmpty())
    }

    @Test
    fun unsupportedProduct_emptyPlan() {
        // MC01(0x110) 官方 isSupport=false → 不查
        assertTrue(XiberiaFeatureQueryPlan.planFor(XiberiaProductCatalog.PID_MC01).isEmpty())
    }

    @Test
    fun mc05_plan_onlyContainsSupportedGets() {
        val plan = XiberiaFeatureQueryPlan.planFor(XiberiaProductCatalog.PID_MC05)
        assertTrue("MC05 至少含若干查询项", plan.isNotEmpty())
        // MC05 无降噪（noiseControl=false）→ 不含降噪项
        assertFalse(plan.any { it.key == "melodyplus_xi_noise" })
        // MC05 无空间音频
        assertFalse(plan.any { it.key == "melodyplus_xi_spatial" })
        // MC05 有游戏模式 / 低音 / LDAC
        assertTrue(plan.any { it.key == "melodyplus_xi_game" })
        assertTrue(plan.any { it.key == "melodyplus_xi_bass" })
        assertTrue(plan.any { it.key == "melodyplus_xi_ldac" })
    }

    @Test
    fun mc05_ldacIsBool_gameIsBool_volumeIsLevel() {
        val plan = XiberiaFeatureQueryPlan.planFor(XiberiaProductCatalog.PID_MC05).associateBy { it.key }
        assertEquals(XiberiaFeatureQueryPlan.PayloadKind.BOOL, plan["melodyplus_xi_ldac"]?.kind)
        assertEquals(XiberiaFeatureQueryPlan.PayloadKind.BOOL, plan["melodyplus_xi_game"]?.kind)
        assertEquals(XiberiaFeatureQueryPlan.PayloadKind.LEVEL, plan["melodyplus_xi_prompt_tone"]?.kind)
    }

    @Test
    fun allPlanItemsHaveNonNullCmdGet() {
        XiberiaProductCatalog.supported().forEach { p ->
            XiberiaFeatureQueryPlan.planFor(p.productId).forEach { q ->
                assertTrue("${p.model}/${q.key} 必须有 cmdGet", q.cmdGet > 0)
            }
        }
    }

    @Test
    fun commandCodesAreDistinctAndNonEmpty() {
        val codes = XiberiaFeatureQueryPlan.commandCodesFor(XiberiaProductCatalog.PID_MC05)
        assertTrue(codes.isNotEmpty())
        assertEquals(codes.size, codes.distinct().size)
    }

    @Test
    fun everySupportedModel_planIsSubsetOfCatalogItems() {
        XiberiaProductCatalog.supported().forEach { p ->
            val catalogKeys = XiberiaProductCatalog.panelItems(p.productId).map { it.key }.toSet()
            XiberiaFeatureQueryPlan.planFor(p.productId).forEach { q ->
                assertTrue("${p.model} 查询项 ${q.key} 必须来自面板项", q.key in catalogKeys)
            }
        }
    }

    @Test
    fun dm03_hasNoiseQuery_aswitches() {
        val plan = XiberiaFeatureQueryPlan.planFor(XiberiaProductCatalog.PID_DM03).associateBy { it.key }
        // DM03 有降噪（noiseControl=true, count=3）→ CHOICE → LEVEL
        assertEquals(XiberiaFeatureQueryPlan.PayloadKind.LEVEL, plan["melodyplus_xi_noise"]?.kind)
    }
}