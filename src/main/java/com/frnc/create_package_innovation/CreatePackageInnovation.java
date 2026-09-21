package com.frnc.create_package_innovation;

import com.frnc.create_package_innovation.partial.PartialOrderTracker;
import com.frnc.create_package_innovation.partial.PartialRepackager;
import com.frnc.create_package_innovation.pool.SharedPackagePool;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(CreatePackageInnovation.MOD_ID)
public class CreatePackageInnovation
{
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "create_package_innovation";

    // SharedPackagePool / PartialOrderTracker / PartialRepackager 以及各 mixin 都会引用这两个成员，
    // 所以必须是 public。
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 打开后打印逐次入池 / 取包 / 渐进合成的详细日志（{@code [CPI-POOL]}、{@code [CPI-PARTIAL]} 行）。
     * 默认关闭以免刷屏；排查并行或部分重组问题时改为 {@code true} 重新编译。
     */
    public static boolean DEBUG_LOGGING = false;

    public CreatePackageInnovation(FMLJavaModLoadingContext context)
    {
        // 后续的 DeferredRegister 一律注册到 context.getModEventBus()；
        // 配置用 context.registerConfig(ModConfig.Type.COMMON, Config.SPEC) 注册。

        LOGGER.info("Create Package Innovation initialized");
    }
}
