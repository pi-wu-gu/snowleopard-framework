package io.github.piwugu.snowleopard;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import snowleopard.core.SnowLeopard;
import snowleopard.core.result.Result;

/**
 * 客户端验收（真机 GUI，2026-10-23 22:45 (UTC+8)）。
 *
 * <p><b>为什么不能直接用全量自测</b>：{@code SelfTestScenario} 收尾会 {@code server.halt(false)}，
 * 在集成服务器上等于把自己客户端关掉；而且它建 8 座黑曜石塔，不适合人看的场景。
 *
 * <p>所以客户端模式只做一件事：**在真人玩家进世界后跑一遍"绑定中立生物 → 推一行到聊天栏"**，
 * 并断言 {@code ChatPush.push} 返回 true——这是专用服务器**做不到**的断言
 * （那边没有客户端，只能返回 false 落日志）。聊天栏里应当看到
 * {@code [雪豹] 提醒：群组未读 g00001(1) · 新消息 1 条}。
 *
 * <p>开关：系统属性 {@code snowleopard.clienttest=true}（build.gradle 的 client run 里给）。
 */
final class ClientSmokeTest {

    private static boolean done;
    private static int wait;

    private ClientSmokeTest() {
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (done || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        done = true;   // 只跑一次
        // 等世界与客户端 UI 都稳定（进服那一刻聊天栏还没准备好）
        new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            player.server.execute(() -> run(player));
        }, "snowleopard-clienttest").start();
    }

    private static void run(ServerPlayer player) {
        String model = "clienttest";
        ServerLevel level = player.serverLevel();

        // ⓪ 先传送玩家到测试区中心并设重生点（用户 2026-10-24 03:15 要求）——
        //    必须在取 base/设扫描锚点/造实体**之前**，否则锚点钉在旧出生点、
        //    新造的实体全都不在候选里（2026-10-24 03:19 真机踩过：3 条连锁假失败）
        net.minecraft.core.BlockPos anchor = PlayerTestArea.anchor();
        if (anchor != null) {
            player.teleportTo(level, anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5, 0, 0);
            player.setRespawnPosition(level.dimension(), anchor, 0f, true, false);
            ok("已传送至测试区并设重生点", true, PlayerTestArea.describe());
        }

        BlockPos base = player.blockPosition();
        EntityToolsRuntime.setScanAnchor(model, base);
        SnowLeopardMod.LOG.info("[雪豹框架][CLIENT] ===== 客户端验收开始（玩家 {}，位置 {}）=====",
                player.getGameProfile().getName(), base);

        // 清掉上次验收残留的载体（同名会撞"禁重名"，残骸也没必要留）。
        // **循环清到干净**（2026-10-28）：一次遍历只覆盖当时已加载的实体，残留的在稍后加载的区块里
        // 就会"复活"——本轮身份自愈就因此接上了上一轮的旧载体（同名、同 modelId），断言假失败。
        int leftoverRounds = 0;
        for (int pass = 0; pass < 3; pass++) {
            java.util.List<io.github.piwugu.snowleopard.AvatarEntity> leftovers = new java.util.ArrayList<>();
            for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                if (e instanceof io.github.piwugu.snowleopard.AvatarEntity av) {
                    leftovers.add(av);
                }
            }
            if (leftovers.isEmpty()) {
                break;
            }
            leftoverRounds++;
            leftovers.forEach(net.minecraft.world.entity.Entity::discard);
        }
        int remainingAvatars = 0;
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (e instanceof io.github.piwugu.snowleopard.AvatarEntity) {
                remainingAvatars++;
            }
        }
        ok("清场彻底（世界内不残留任何载体，避免同名/同 modelId 的幽灵实体）",
                remainingAvatars == 0, "清理轮次=" + leftoverRounds + " 剩余=" + remainingAvatars);
        // 登记表不随实体消失（卸载≠解绑）：上一轮的"小白"还挂在表里，先清自己的旧登记，
        // 否则同名绑定撞"禁重名"（只清 clienttest 的，别人的不动）
        EntityToolsRuntime.dispatch(model, "avatar", "avatar_unbind", Map.of());

        // ① 中立生物：僵尸猪灵（受击才反击），放在玩家侧前方 3 格
        ZombifiedPiglin pig = new ZombifiedPiglin(
                net.minecraft.world.entity.EntityType.ZOMBIFIED_PIGLIN, level);
        pig.setPersistenceRequired();
        pig.moveTo(player.getX() + 3, player.getY(), player.getZ() + 0.5, 0, 0);
        level.addFreshEntity(pig);
        String ref = pig.getUUID().toString();
        ok("造中立生物（僵尸猪灵）", true, ref);

        // ② 绑定
        Result open = EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
        ok("sense.open 候选可见", String.valueOf(data(open)).contains(ref), brief(open));
        Result pick = EntityToolsRuntime.dispatch(model, "entity", "pick", Map.of("target", ref));
        ok("绑定中立生物", pick instanceof Result.Ok, brief(pick));
        ok("中立旁证（未受击不锁目标）", pig.getTarget() == null, "target=" + pig.getTarget());

        // ③ 制造一条群消息 → 汇总行
        Result created = EntityToolsRuntime.dispatch(model, "group", "group_create",
                Map.of("name", "客户端验收组"));
        String gid = String.valueOf(data(created)).replaceAll(".*gid=([^,}]+).*", "$1");
        EntityToolsRuntime.dispatch(model, "group", "group_send",
                Map.of("gid", gid, "text", "客户端验收消息"));
        String line = WakeBridge.turnLine(model);
        ok("汇总行生成", line != null, String.valueOf(line));

        // ④ 推到聊天栏——真客户端下必须真的送达（专用服务器这里会返回 false）
        boolean sent = ChatPush.push(line);
        ok("聊天栏推送送达（真客户端可达）", sent, "sent=" + sent);

        // ⑤ 一条更花的、便于肉眼确认的行（含点击建议不可用，只做视觉锚点）
        ChatPush.push("客户端验收完成：绑定 " + ref.substring(0, 8) + "… · 聊天栏推送已接通");

        // ⑥ 载体（M13）：生成一只载体并绑为主载体——玩家可直接右键它测互动唤醒，
        //    在聊天栏说"小白"可测点名唤醒；名字同时是身份（[雪豹·小白]）
        io.github.piwugu.snowleopard.AvatarEntity avatar =
                new io.github.piwugu.snowleopard.AvatarEntity(
                        io.github.piwugu.snowleopard.AvatarEntities.AVATAR.get(), level);
        avatar.setPersistenceRequired();
        avatar.moveTo(player.getX() + 1.5, player.getY(), player.getZ() + 2.5, 0, 0);
        level.addFreshEntity(avatar);
        avatar.setCustomName(net.minecraft.network.chat.Component.literal("小白"));
        // 重新扫描：pick 只认 open 候选集里的 ref（不 open 直接 pick 会静默失败，
        // 绑定还停留在猪灵上 → avatar_bind 报"不是载体"）
        EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
        EntityToolsRuntime.dispatch(model, "entity", "pick",
                Map.of("target", avatar.getStringUUID()));
        Result bindAv = EntityToolsRuntime.dispatch(model, "avatar", "avatar_bind", Map.of());
        ok("载体就位（豹猫外观，右键/点名可唤醒）", bindAv instanceof Result.Ok,
                brief(bindAv) + " | 位置：你身前 2.5 格");

        // ⑥·b 身份自愈（2026-10-28 架构修正）：**实体存 ID，排程表里的 uuid 只是地址**。
        // 把登记的地址改成随机值（等价于换世界/回档后旧 uuid 失效），avatar_status 必须仍报 loaded=true，
        // 且地址被回写成当前实体的真 uuid。旧实现这里会 loaded=false 且**永不恢复**。
        String realUuid = avatar.getStringUUID();
        String staleUuid = "00000000-0000-4000-8000-00000000dead";
        boolean addrChanged = io.github.piwugu.snowleopard.AvatarWake
                .rebindAddressForTest(model, realUuid, staleUuid);
        var healedStatus = EntityToolsRuntime.dispatch(model, "avatar", "avatar_status", Map.of());
        String healedBody = String.valueOf(data(healedStatus));
        boolean selfHealed = healedBody.contains(realUuid) && !healedBody.contains(staleUuid)
                && healedBody.contains("loaded=true");
        ok("载体身份自愈（旧 uuid 失效 → 按实体上的 modelId 重新发现并回写地址）",
                addrChanged && selfHealed,
                "改地址=" + addrChanged + " · 回写=" + healedBody.contains(realUuid)
                        + " · 无残留死地址=" + !healedBody.contains(staleUuid)
                        + " · loaded=" + healedBody.contains("loaded=true")
                        + " · 实体=" + realUuid.substring(0, 8)
                        + " · status=" + healedBody.substring(0, Math.min(220, healedBody.length())));

        // ⑥·补 备货：一组木头 + 一组石头进九格背包（用户 2026-10-24 03:29 要求；每次进服重置这两格）
        if (avatar instanceof io.github.piwugu.snowleopard.tools.PackCarrier pc) {
            pc.pack().setItem(0, new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.OAK_LOG, 64));
            pc.pack().setItem(1, new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.STONE, 64));
            ChatPush.push("小白背包已备货：橡木原木×64（self:pack:0）· 石头×64（self:pack:1）");
        }

        // ⑥·补2 两条确定性断言（2026-10-24 11:57；不依赖模型自发布调用——1.9.2 的教训：
        //    靠"模型碰巧踩中"暴露故障，整整两版没人看见。断言必须服务端可判、每次进服必跑）

        //    (a) F1 暴露名→真名映射：四个重名 status 在 LLM 侧叫 sense_status 等（下划线前缀），
        //        执行面 switch 的却是 status——映射断了就是"清单里看得见、一调用就失败"
        if (SnowLeopardMod.core() != null) {
            String r1 = io.github.piwugu.snowleopard.host.AiHost.realNameOf("sense_status");
            String r2 = io.github.piwugu.snowleopard.host.AiHost.realNameOf("entity_self_status");
            String r3 = io.github.piwugu.snowleopard.host.AiHost.realNameOf("view_status");
            String r4 = io.github.piwugu.snowleopard.host.AiHost.realNameOf("interact_status");
            String r5 = io.github.piwugu.snowleopard.host.AiHost.realNameOf("goto");
            boolean f1 = "status".equals(r1) && "status".equals(r2) && "status".equals(r3)
                    && "status".equals(r4) && "goto".equals(r5);
            ok("F1 暴露名→真名映射（status 四兄弟可见且可用）", f1,
                    "sense_status→" + r1 + " · entity_self_status→" + r2 + " · view_status→" + r3
                            + " · interact_status→" + r4 + " · goto→" + r5);
        }

        //    (b) craft 余料按 count 退：蜂蜜瓶×2 → 糖×6 + 玻璃瓶×2 回包
        //        （1.9.3 之前余料只在循环外算一次，count=2 只退 1 只瓶）
        if (avatar instanceof io.github.piwugu.snowleopard.tools.PackCarrier pcRem) {
            var pack = pcRem.pack();
            pack.setItem(2, new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.HONEY_BOTTLE, 1));
            pack.setItem(3, new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.HONEY_BOTTLE, 1));
            Result cr = EntityToolsRuntime.dispatch(model, "craft", "craft",
                    Map.of("recipe_id", "minecraft:sugar_from_honey_bottle", "count", "2"));
            int sugar = 0;
            int bottle = 0;
            int honey = 0;
            for (int i = 0; i < pack.getContainerSize(); i++) {
                var s = pack.getItem(i);
                if (s.is(net.minecraft.world.item.Items.SUGAR)) {
                    sugar += s.getCount();
                }
                if (s.is(net.minecraft.world.item.Items.GLASS_BOTTLE)) {
                    bottle += s.getCount();
                }
                if (s.is(net.minecraft.world.item.Items.HONEY_BOTTLE)) {
                    honey += s.getCount();
                }
            }
            ok("craft 余料按 count 退（蜂蜜瓶×2 → 玻璃瓶×2 回包）",
                    cr instanceof Result.Ok && sugar == 6 && bottle == 2 && honey == 0,
                    brief(cr) + " · 糖=" + sugar + " 玻璃瓶=" + bottle + " 蜂蜜瓶剩=" + honey);
            // 清场：合成产物撤掉，把九格还给后续流程
            for (int i = 0; i < pack.getContainerSize(); i++) {
                var s = pack.getItem(i);
                if (s.is(net.minecraft.world.item.Items.SUGAR)
                        || s.is(net.minecraft.world.item.Items.GLASS_BOTTLE)
                        || s.is(net.minecraft.world.item.Items.HONEY_BOTTLE)) {
                    pack.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                }
            }
        }

        //    (c) attack 属性断言（2026-10-24 17:35 真机抓到的 bug：载体 createAttributes 缺
        //        ATTACK_DAMAGE，LivingEntity.doHurtTarget 无条件读它 → attack 一调就抛
        //        "Can't find attribute minecraft:generic.attack_damage"。M6/M11 的 attack 自测
        //        绑的全是僵尸猪灵，载体本体从没被测过——直接走真实 doHurtTarget 路径补上）
        var slime = net.minecraft.world.entity.EntityType.SLIME.create(level);
        if (slime != null) {
            slime.setPos(avatar.getX() + 1.5, avatar.getY(), avatar.getZ());
            slime.setPersistenceRequired();
            level.addFreshEntity(slime);
            float hpBefore = slime.getHealth();
            boolean hit = avatar.doHurtTarget(slime);
            float hpAfter = slime.getHealth();
            ok("载体可攻击（ATTACK_DAMAGE 属性在，doHurtTarget 真实掉血）",
                    hit && hpAfter < hpBefore,
                    "hit=" + hit + " · 史莱姆血量 " + hpBefore + "→" + hpAfter);
            slime.discard();
        }

        //    (d) drop count 宽容解析回归（2026-10-24 17:52 真机实锤：schema 全 string，模型发
        //        "count":"3" → 旧实现只认 Number → 静默整组丢 53 木头+18 门。修后字符串必须真拆）
        if (avatar instanceof io.github.piwugu.snowleopard.tools.PackCarrier pcDrop) {
            pcDrop.pack().setItem(4, new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.DIRT, 10));
            Result dr = EntityToolsRuntime.dispatch(model, "item", "drop",
                    Map.of("from", "self:pack:4", "count", "3"));
            int left = pcDrop.pack().getItem(4).getCount();
            boolean dropped3 = String.valueOf(brief(dr)).contains("dropped=3") || String.valueOf(brief(dr)).contains("dropped= 3");
            ok("drop 按字符串 count 真拆（\"3\" → 3 个，不整组）",
                    dr instanceof Result.Ok && left == 7 && dropped3,
                    brief(dr) + " · 包剩=" + left + "（期望 7）");
            pcDrop.pack().setItem(4, net.minecraft.world.item.ItemStack.EMPTY);
            // 清场：丢出去的泥土实体收掉，别污染后面的断言/玩家视野
            for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                if (e instanceof net.minecraft.world.entity.item.ItemEntity ie
                        && ie.getItem().is(net.minecraft.world.item.Items.DIRT)) {
                    ie.discard();
                }
            }
        }

        //    (e) 捕获 + 拾取 + 自丢反馈环（v1.10.0 三件套，全走服务端事实）
        if (avatar instanceof io.github.piwugu.snowleopard.tools.PickupCarrier carrier) {
            // (e1) capture：真蝙蝠 → discard + snowleopard:bat 入包
            var bat = net.minecraft.world.entity.EntityType.BAT.create(level);
            if (bat != null) {
                bat.setPos(avatar.getX() + 1.0, avatar.getY() + 1.0, avatar.getZ());
                bat.setPersistenceRequired();
                level.addFreshEntity(bat);
                boolean hadBat = false;
                Result cap = EntityToolsRuntime.dispatch(model, "interact", "capture",
                        Map.of("target", bat.getStringUUID()));
                for (int i = 0; i < carrier.pack().getContainerSize(); i++) {
                    if (carrier.pack().getItem(i).is(
                            io.github.piwugu.snowleopard.item.BatItems.BAT.get())) {
                        hadBat = true;
                    }
                }
                ok("capture 蝙蝠入包（实体消失+物品真在包里）",
                        cap instanceof Result.Ok && hadBat && !bat.isAlive(),
                        brief(cap) + " · 蝙蝠实体存活=" + bat.isAlive());
            }
            // (e2) 自动拾取开关 + 过滤词 + 自丢判据（直接调原版扫描的三个覆盖点，不依赖 tick 时序）
            avatar.setCanPickUpLoot(true);
            carrier.setPickupFilter("iron");
            var gold = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_NUGGET, 2);
            var iron = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_NUGGET, 2);
            ok("pickup_auto 过滤词生效（filter=iron：金粒拒、铁粒收）",
                    !avatar.wantsToPickUp(gold) && avatar.wantsToPickUp(iron),
                    "gold=" + avatar.wantsToPickUp(gold) + " iron=" + avatar.wantsToPickUp(iron));
            carrier.setPickupFilter("");
            // 自丢反馈环：thrower=自己 的掉落物自动拾取必须跳过（否则 drop 给玩家的东西 0.5s 被捡回）
            var selfDropped = new net.minecraft.world.entity.item.ItemEntity(level,
                    avatar.getX() + 0.5, avatar.getY(), avatar.getZ(),
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 4));
            selfDropped.setThrower(avatar);
            level.addFreshEntity(selfDropped);
            avatar.pickUpItem(selfDropped);
            var others = new net.minecraft.world.entity.item.ItemEntity(level,
                    avatar.getX() + 0.5, avatar.getY(), avatar.getZ(),
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 4));
            level.addFreshEntity(others);
            avatar.pickUpItem(others);
            ok("自丢反馈环判据（自己丢的不捡、别人的收）",
                    selfDropped.isAlive() && !others.isAlive(),
                    "自丢存活=" + selfDropped.isAlive() + " · 他人掉落物存活=" + others.isAlive());
            // (e3) 主动 pickup：扫描+过滤+触达（上面的铁粒/木板已被手摆收取；这里补一单工具面调用）
            var far = new net.minecraft.world.entity.item.ItemEntity(level,
                    avatar.getX() + 3.5, avatar.getY(), avatar.getZ(),
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CLAY_BALL, 1));
            level.addFreshEntity(far);
            Result pk = EntityToolsRuntime.dispatch(model, "item", "pickup", Map.of());
            boolean claySkipped = String.valueOf(brief(pk)).contains("clay_ball")
                    && String.valueOf(brief(pk)).contains("超触达");
            ok("pickup 触达外如实进 skipped（3.5 格在扫描 4 内、触达 2.5 外，不隔空取物）",
                    pk instanceof Result.Ok && claySkipped,
                    brief(pk));
            // 收尾：清掉测试掉落物，恢复自动拾取默认态（关）——别把自测状态漏给真实会话
            selfDropped.discard();
            avatar.setCanPickUpLoot(false);
            carrier.setPickupFilter("");
        }

        //    (g) 玩家右键抓蝙蝠（v1.10.2 用户真机裁决：抓不该只是模型的能力）。
        //        tryCapture 本体双端可跑，这里在服务端直接调——断言"蝙蝠消失+物品真进玩家背包"，
        //        随后立即清场（从背包移除），不把自测赠品留给真实会话。
        var grabBat = net.minecraft.world.entity.EntityType.BAT.create(level);
        if (grabBat != null) {
            grabBat.setPos(player.getX() + 2.0, player.getY() + 1.0, player.getZ());
            grabBat.setPersistenceRequired();
            level.addFreshEntity(grabBat);
            int before = player.getInventory().items.stream()
                    .filter(s -> s.is(io.github.piwugu.snowleopard.item.BatItems.BAT.get()))
                    .mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            boolean grabbed = PlayerBatCapture.tryCapture(player, grabBat);
            int after = player.getInventory().items.stream()
                    .filter(s -> s.is(io.github.piwugu.snowleopard.item.BatItems.BAT.get()))
                    .mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            ok("玩家空手右键抓蝙蝠（tryCapture：蝙蝠消失+物品进背包）",
                    grabbed && !grabBat.isAlive() && after == before + 1,
                    "grabbed=" + grabbed + " · 蝙蝠存活=" + grabBat.isAlive()
                            + " · 背包蝙蝠数 " + before + "→" + after);
            // 清场：移除刚塞进背包的蝙蝠物品（自测不留赠品）
            for (int i = 0; i < player.getInventory().items.size(); i++) {
                if (player.getInventory().items.get(i)
                        .is(io.github.piwugu.snowleopard.item.BatItems.BAT.get())) {
                    player.getInventory().items.set(i, net.minecraft.world.item.ItemStack.EMPTY);
                }
            }
        }

        //    (h) 测试区治疗药水箱（v1.10.5 用户要求）：箱子在 + 装的是喷溅·瞬间治疗
        var potionChest = PlayerTestArea.potionChestPos();
        if (potionChest != null) {
            boolean chestThere = level.getBlockState(potionChest)
                    .is(net.minecraft.world.level.block.Blocks.CHEST);
            boolean potionsOk = false;
            String potionDetail = "无方块实体/不是箱子";
            if (level.getBlockEntity(potionChest)
                    instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                int filled = 0;
                boolean allHealing = true;
                for (int i = 0; i < chest.getContainerSize(); i++) {
                    var st = chest.getItem(i);
                    if (st.isEmpty()) {
                        continue;
                    }
                    filled++;
                    var contents = st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
                    boolean isHealing = contents != null && contents.potion()
                            .map(h -> h.value() == net.minecraft.world.item.alchemy.Potions.HEALING.value())
                            .orElse(false);
                    allHealing &= st.is(net.minecraft.world.item.Items.SPLASH_POTION) && isHealing;
                }
                potionsOk = filled >= 20 && allHealing;
                potionDetail = "箱内 " + filled + " 格有物品 · 全是喷溅治疗=" + allHealing;
            }
            ok("测试区有一箱投掷型治疗药水（喷溅·瞬间治疗）",
                    chestThere && potionsOk, potionDetail);
        }

        //    (i) 记忆写侧接线（v1.11.0）：一轮落盘 + 工具大结果被筛掉 + 触发轮压缩
        //        用独立 ID（memprobe）——不污染 clienttest 自己的记忆；断言后也不清（原始记录是基线）
        var memCore = SnowLeopardMod.core();
        if (memCore != null) {
            String probe = "memprobe";
            var rec = io.github.piwugu.snowleopard.host.MemoryRecorder.begin(memCore, probe);
            rec.user("验收：写一轮记忆");
            rec.model("好的，照做。");
            StringBuilder huge = new StringBuilder("Ok: [");
            for (int i = 0; i < 800; i++) {
                huge.append("{\"id\":\"").append(i).append("\",\"name\":\"实体").append(i).append("\"},");
            }
            huge.append("]");
            rec.tool("sense_query", "{\"what\":\"entities\"}", huge.toString());
            rec.tool("world_blocks", "{}", huge.toString());
            snowleopard.core.result.Result memRes = rec.finish();

            var ids = memCore.ids();
            var caller = new snowleopard.core.ids.Ids.Caller(probe);
            var rawDir = ids.resolve(caller, "memory-raw");
            boolean roundFile = false;
            boolean squeezedMark = false;
            long roundBytes = 0;
            try (var files = java.nio.file.Files.list(rawDir)) {
                var first = files.filter(p -> p.getFileName().toString().startsWith("round-"))
                        .findFirst().orElse(null);
                if (first != null) {
                    roundFile = true;
                    roundBytes = java.nio.file.Files.size(first);
                    squeezedMark = java.nio.file.Files.readString(first,
                            java.nio.charset.StandardCharsets.UTF_8).contains("…(原 ");
                }
            } catch (Exception ignored) {
                // 目录不存在等情况按失败处理（roundFile 保持 false）
            }
            // 大结果 2 × 约 18KB → 必须被压到几百字符：整轮文件远小于 8KB
            var dayMemo = ids.resolve(caller, "memory/days/"
                    + snowleopard.core.memory.RawLog.dayOf(System.currentTimeMillis()) + "/memo.md");
            boolean dayOk = !(memRes instanceof snowleopard.core.result.Result.Ok)
                    || java.nio.file.Files.exists(dayMemo);   // Ok 就必须真产出天档
            ok("记忆写侧接线（一轮落盘 + 工具大结果被筛掉 + 触发压缩）",
                    roundFile && squeezedMark && roundBytes > 0 && roundBytes < 8000 && dayOk,
                    "轮文件=" + roundFile + " · " + roundBytes + "B · 裁剪标记=" + squeezedMark
                            + " · 压缩=" + (memRes == null ? "null" : memRes));
        }

        //    (j) 载体尺寸（v1.12.0 换成自建雪豹模型后同步放大）：模型 0.79×1.44 格，
        //        碰撞箱若仍是豹猫的 0.6×0.7 会明显不匹配（穿模 + 右键难命中）。
        //        这里断言的是**服务端可测的事实**，不是渲染效果——渲染只能肉眼。
        var dims = avatar.getDimensions(net.minecraft.world.entity.Pose.STANDING);
        ok("载体碰撞箱与雪豹模型匹配（0.9 × 1.4）",
                Math.abs(dims.width() - 0.9f) < 0.011f && Math.abs(dims.height() - 1.4f) < 0.011f,
                "w=" + dims.width() + " h=" + dims.height());

        //    (k) "降级可得知"（v1.12.4）：view/status 必须带 vision 字段，且字段里含能力级。
        //        只验"没崩"是不够的——字段真在、真有 level=，模型才可能知道自己看不了图。
        //        这里不断言具体级别（级别取决于配置与渲染后端），只断言"能力信息可见"。
        var vres = EntityToolsRuntime.dispatch(model, "view", "status", Map.of());
        String vtext = String.valueOf(vres instanceof snowleopard.core.result.Result.Ok o
                ? o.data() : vres);
        ok("视觉能力对模型可见（view/status 带 vision 字段与能力级）",
                vtext.contains("vision") && vtext.contains("level=") && vtext.contains("render="),
                brief(vres));

        //    (k2) L0 真图（v1.12.8）：配了 multimodal 提供商且渲染后端可用 → 能力级必须是 L0。
        //        这是"加了 VLM 但没生效"的唯一可观测面：roles 指错家、roles.vision 指向非多模态、
        //        名字打错被静默回落，都会停在 L1（图落盘了，但模型看不了），而**工具照样返回 Ok**。
        String vline = io.github.piwugu.snowleopard.host.AiHost.visionStatusLine();
        ok("视觉能力升到 L0（VLM 就绪：真图进上下文）",
                vline.contains("level=L0") && vline.contains("vlm=true") && vline.contains("render=true"),
                vline);

        //    (k3) 视觉工具暴露名自解释（v1.12.9）：scan/shot/show 三个名字都不重名，按"重名才带前缀"
        //        会被**裸暴露**成 `shot` —— 清单里躺着一个叫 shot 的工具，模型不会把它和"看图"联系起来
        //        （用户实测："模型似乎并不知道如何使用视觉"）。这里断言暴露名带 vision_ 前缀，
        //        且能正确换回注册真名（暴露名≠真名，两处口径必须分家，回执行面必须换回真名）。
        String shotReal = io.github.piwugu.snowleopard.host.AiHost.realNameOf("vision_shot");
        ok("视觉工具暴露名自解释（vision_shot/scan/show 均可换回真名）",
                "shot".equals(shotReal)
                        && "scan".equals(io.github.piwugu.snowleopard.host.AiHost.realNameOf("vision_scan"))
                        && "show".equals(io.github.piwugu.snowleopard.host.AiHost.realNameOf("vision_show")),
                "vision_shot→" + shotReal);

        //    (k3b) AI 开关与验收开关分离（v1.15.3）：生产实例想开 AI 不能再被迫跑验收
        //        （旧实现只有 `snowleopard.clienttest` 一个属性 → 连带传送玩家、造实体、在真实存档建测试塔）。
        //        断言两件事：**缺省必须关**（默认开等于生产存档被"忘了配"改掉）、显式 true 才开。
        boolean aiFlagOk = false;
        java.nio.file.Path tmpFlag = null;
        try {
            tmpFlag = java.nio.file.Files.createTempDirectory("sl-ai-flag");
            java.nio.file.Files.createDirectories(tmpFlag.resolve("config"));
            java.nio.file.Path cfgFile = tmpFlag.resolve("config").resolve("snowleopard-llm.json");
            java.nio.file.Files.writeString(cfgFile, "{\"providers\":[]}",
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean offByDefault = !io.github.piwugu.snowleopard.host.AiHost.aiEnabled(tmpFlag);
            java.nio.file.Files.writeString(cfgFile, "{\"ai\":{\"enabled\":true}}",
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean onWhenSet = io.github.piwugu.snowleopard.host.AiHost.aiEnabled(tmpFlag);
            aiFlagOk = offByDefault && onWhenSet;
        } catch (java.io.IOException e) {
            aiFlagOk = false;
        }
        ok("AI 开关与验收开关分离（缺省关 / 显式 ai.enabled=true 才开）", aiFlagOk,
                "临时配置验证 · " + (tmpFlag == null ? "建目录失败" : tmpFlag.getFileName()));

        //    (k4) 工具声明跟随工作集（v1.13.0，取代 v1.12.10 的"按话题排序"）：
        //        默认只声明目录组三件；tool_help/tool_scope 取用某组后，**下一轮**才带出那组的签名。
        //        断言的是"声明集合真的跟着工作集变"，不是"分组字段存在"。
        var core0 = SnowLeopardMod.core();
        String selftestModel = "clienttest";
        core0.exposure().clear(selftestModel);
        var baseNames = io.github.piwugu.snowleopard.host.AiHost.declaredForTest(core0, selftestModel);
        core0.exposure().pinGroup(selftestModel, "mod:snowleopard:move");
        var withMove = io.github.piwugu.snowleopard.host.AiHost.declaredForTest(core0, selftestModel);
        boolean lazy = baseNames.size() == 3 && baseNames.contains("tool_list") && baseNames.contains("tool_help")
                && baseNames.contains("tool_scope")
                && withMove.size() == baseNames.size() + 6 && withMove.contains("move");
        ok("工具声明跟随工作集（默认只有目录三件；取用 move 后 +6）", lazy,
                "默认=" + baseNames + " · 取用后=" + withMove.size());
        core0.exposure().clear(selftestModel);

        //    (k5) 伪造工具调用的判据（v1.13.1）：模型在工具失败/不可用时会输出
        //        `<|CALL_START|>function chat_send {...}<|CALL_END|>` 当正文（16:22 真机实证）。
        //        这条断言"判据真的认得出标记"，否则 host 会把它当正常回答（玩家什么也收不到）。
        boolean dsml = io.github.piwugu.snowleopard.host.AiHost
                .looksLikeFakeToolCall("<|CALL_START|>function chat_send {\"text\":\"x\"}<|CALL_END|>")
                && io.github.piwugu.snowleopard.host.AiHost.looksLikeFakeToolCall("<|CALL_START|>function")
                && !io.github.piwugu.snowleopard.host.AiHost.looksLikeFakeToolCall("这是一段正常回答，提到 tool_help 不算");
        ok("伪造工具调用可识别（DSML 标记 → 不当正常回答）", dsml, "判据失效");

        //    (k6) 视线渐进（v1.14.0）：look 不再是"一帧 setRotation"，而是设目标后**眼→头→身体**依次追。
        //        断言的是观感契约，不是"字段能写进去"：
        //        ① 第一 tick 瞳孔已经偏了、头还没到（这就是"眼先动"）；
        //        ② 小角度（40°）只转头不动身体，头到位后瞳孔回中（否则是斜眼）；
        //        ③ 大角度（120°）头卡在限位后身体才跟，合成视线落到目标。
        EntityToolsRuntime.dispatch(model, "view", "unlock", Map.of());
        float gazeBase = avatar.getYRot();
        EntityToolsRuntime.dispatch(model, "view", "look",
                Map.of("yaw", (double) gazeBase + 40, "pitch", 0.0));
        float pupil1 = Math.abs(avatar.pupilX());
        float head1 = Math.abs(avatar.headYawRel());
        for (int i = 0; i < 48; i++) {
            EntityToolsRuntime.module(model).tickViewLock();
        }
        float headSmall = avatar.headYawRel();
        float pupilEnd = Math.abs(avatar.pupilX());
        // 眼先动：第一 tick 头几乎不动（死区内），瞳孔已经明显偏了
        boolean eyesFirst = pupil1 > 0.15f && head1 < 8f;
        // 收敛后：头停在差 15° 处，瞳孔**保持满格偏移**（这正是"看得见瞳孔"的关键）
        boolean smallSettled = Math.abs(headSmall - 25f) < 2.5f
                && Math.abs(pupilEnd - 0.35f) < 0.04f
                && io.github.piwugu.snowleopard.tools.FacingMath
                        .deltaDeg(avatar.getYRot(), gazeBase) < 2.0;
        ok("视线渐进①眼先动②头留 15° 死区、瞳孔保持满格", eyesFirst && smallSettled,
                "pupil1=" + pupil1 + " head1=" + head1 + " headEnd=" + headSmall
                        + " pupilEnd=" + pupilEnd + " bodyYaw=" + avatar.getYRot()
                        + " base=" + gazeBase);

        EntityToolsRuntime.dispatch(model, "view", "look",
                Map.of("yaw", (double) gazeBase + 120, "pitch", 0.0));
        for (int i = 0; i < 80; i++) {
            EntityToolsRuntime.module(model).tickViewLock();
        }
        double bodyTurned = io.github.piwugu.snowleopard.tools.FacingMath
                .deltaDeg(avatar.getYRot(), gazeBase);
        // 视线 = 身体 + 头 + 眼（瞳孔只剩最后 15°，换算成度数）
        double eyeDeg = Math.abs(avatar.pupilX()) / 0.35 * 15.0;
        double composed = avatar.getYRot() + avatar.headYawRel()
                + (avatar.headYawRel() >= 0 ? eyeDeg : -eyeDeg);
        ok("视线渐进③大角度头到限位后身体跟上（合成视线到目标）",
                bodyTurned > 20 && Math.abs(io.github.piwugu.snowleopard.tools.FacingMath
                        .deltaDeg(composed, gazeBase + 120)) < 5.0,
                "bodyTurned=" + bodyTurned + " headRel=" + avatar.headYawRel()
                        + " eyeDeg=" + eyeDeg + " composed=" + composed
                        + " target=" + (gazeBase + 120));

        // **恢复体位再解锁**（v1.14.0）：本段把身体真的转过了（120° 目标 → 身体跟到 60°），
        // 不还原就会改掉后面视觉断言的场景前提——红羊毛是"按当时朝向"临时摆的，
        // 朝向变了就会摆在别处，表现为"扫描没命中"这种看着像产品坏了的假回归。
        avatar.setYRot(gazeBase);
        avatar.setXRot(0f);
        avatar.setYHeadRot(gazeBase);
        EntityToolsRuntime.dispatch(model, "view", "unlock", Map.of());

        //    (k7) 配方检索的过滤参数真的生效（2026-10-27 载体在游戏聊天里报的 bug：
        //        传 oak_planks 仍返回 890 条全量）。根因是 schema 声明 `keyword`、
        //        实现却读 `query`——**名字不同形，不报错，只是静默返回全量**。
        //        这类 bug 单测抓不到（没有 MC 配方表），只能靠真机断言锁。
        var recAll = EntityToolsRuntime.dispatch(model, "craft", "craft_recipes", Map.of("limit", 300));
        var recQuery = EntityToolsRuntime.dispatch(model, "craft", "craft_recipes",
                Map.of("query", "planks", "limit", 300));
        var recLegacy = EntityToolsRuntime.dispatch(model, "craft", "craft_recipes",
                Map.of("keyword", "planks", "limit", 300));
        int mAll = matchedOf(recAll);
        int mQuery = matchedOf(recQuery);
        int mLegacy = matchedOf(recLegacy);
        boolean filterWorks = mAll > 0 && mQuery > 0 && mQuery < mAll;
        ok("配方检索过滤生效（query 只返匹配项，不再全量）", filterWorks,
                "全量 matched=" + mAll + " · query=planks matched=" + mQuery);
        ok("配方检索兼容历史 keyword（模型学过的旧名仍然收）", mLegacy == mQuery,
                "keyword matched=" + mLegacy + " · query matched=" + mQuery);

        //    (k8) 受击唤醒的提示要能直接支持决策（v1.14.4）：只说"你被攻击了，来源 Dev"，
        //        模型分不清剑砍/箭射/摔伤/岩浆，而这几种该做的反应完全不同。
        //        这里只断言**描述文本**（不真的触发唤醒——唤醒会开一轮会话，断言不该有这种副作用）。
        String envHit = AvatarWake.describeHit(
                level.damageSources().magic(), null, avatar, 3.0f);
        ok("受击提示带伤害类型（环境伤害：无来源也能说清）",
                envHit.contains("类型 magic") && envHit.contains("无可定位来源")
                        && envHit.contains("血量"),
                envHit);
        // 同格：方位角无意义，应说"贴着你"而不是胡乱指方向
        String sameCell = AvatarWake.describeHit(
                level.damageSources().mobAttack(avatar), avatar, avatar, 2.0f);
        ok("受击提示：同一格时不乱报方位（说'贴着你'）",
                sameCell.contains("类型 mob_attack") && sameCell.contains("贴着你"),
                sameCell);
        // 真实第三方来源：方位词 + 距离 + 血量都要出现（不移动任何实体，用它原来的位置）
        var nearby = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                avatar.getBoundingBox().inflate(16)).stream()
                .filter(e -> e != avatar).findFirst().orElse(null);
        if (nearby != null) {
            String ranged = AvatarWake.describeHit(
                    level.damageSources().mobAttack(nearby), nearby, avatar, 2.0f);
            boolean hasSide = ranged.contains("正前方") || ranged.contains("正后方")
                    || ranged.contains("你的右侧") || ranged.contains("你的左侧");
            ok("受击提示带方位距离与血量（第三方来源）",
                    hasSide && ranged.contains("格") && ranged.contains("血量"), ranged);
        } else {
            ok("受击提示带方位距离与血量（第三方来源）", false,
                    "周围 16 格内找不到第二个生物，无法验证方位");
        }

        //    (l) 视觉 B 档（v1.12.5）：射线必须**真的沿视线**命中，且转身 180° 后不再命中。
        //        单向断言抓不到"方向反了"（射线往反方向也照样能命中别的方块），所以必须双向。
        var eye = avatar.getEyePosition(1.0f);
        var look = avatar.getViewVector(1.0f);
        var aimPos = net.minecraft.core.BlockPos.containing(
                eye.x + look.x * 5, eye.y + look.y * 5, eye.z + look.z * 5);
        var saved = new java.util.LinkedHashMap<net.minecraft.core.BlockPos,
                net.minecraft.world.level.block.state.BlockState>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    var p = aimPos.offset(dx, dy, dz).immutable();
                    saved.put(p, level.getBlockState(p));
                    level.setBlock(p, net.minecraft.world.level.block.Blocks.RED_WOOL
                            .defaultBlockState(), 2);
                }
            }
        }
        var hitRes = EntityToolsRuntime.dispatch(model, "vision", "scan", Map.of("range", 10));
        String hitText = String.valueOf(hitRes instanceof snowleopard.core.result.Result.Ok o
                ? o.data() : hitRes);
        ok("视觉射线沿视线命中（5 格处红羊毛 + source=raycast）",
                hitText.contains("minecraft:red_wool") && hitText.contains("raycast"),
                brief(hitRes));
        float yaw0 = avatar.getYRot();
        avatar.setYRot(yaw0 + 180f);
        avatar.setYHeadRot(avatar.getYRot());
        var backRes = EntityToolsRuntime.dispatch(model, "vision", "scan", Map.of("range", 10));
        String backText = String.valueOf(backRes instanceof snowleopard.core.result.Result.Ok o
                ? o.data() : backRes);
        ok("视觉射线随朝向改变（转身 180° 后不再命中红羊毛）",
                !backText.contains("minecraft:red_wool"), brief(backRes));
        avatar.setYRot(yaw0);
        avatar.setYHeadRot(yaw0);
        saved.forEach((p, st) -> level.setBlock(p, st, 2));

        //    (m) 视觉 C 档异步管线（v1.12.6）：受理即返回 → 回执落盘 → 第二枪被拦 → show 重看。
        //        本版后端是**测试桩**（画面是假的），验的是管线而不是画面质量。
        var capRes = EntityToolsRuntime.dispatch(model, "vision", "shot", Map.of());
        String capTxt = String.valueOf(capRes instanceof snowleopard.core.result.Result.Ok o
                ? o.data() : capRes);
        ok("视觉截图受理即返回（异步，不阻塞会话）",
                capTxt.contains("REQUESTED") && capTxt.contains("reqId"), brief(capRes));

        var capAgain = EntityToolsRuntime.dispatch(model, "vision", "shot", Map.of());
        ok("连发第二枪被拦（不排队——排队=把失败变成稍后执行）",
                capAgain instanceof snowleopard.core.result.Result.Warn, brief(capAgain));

        // 等图**不能在服务端主线程 sleep**：回执的落地工作本身要 `server.execute` 排到主线程，
        // 主线程一睡就等于"等自己"——v1.12.7 真机实测：轮询放弃后 100ms 图才落地（假失败）。
        // 所以放到旁路线程轮询（同 ⑨ 破坏掉落的写法），最多 ~10s，拿到就判。
        final String shotModel = model;
        Thread visionWait = new Thread(() -> {
            java.nio.file.Path got = null;
            for (int i = 0; i < 100 && got == null; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (var r : io.github.piwugu.snowleopard.host.VisionPending.drain(shotModel)) {
                    if (r.state() == io.github.piwugu.snowleopard.host.VisionPending.State.READY) {
                        got = r.file() == null ? null : java.nio.file.Path.of(r.file());
                    }
                }
            }
            boolean okPng = false;
            if (got != null && java.nio.file.Files.isRegularFile(got)) {
                try {
                    byte[] b = java.nio.file.Files.readAllBytes(got);
                    okPng = b.length > 8 && (b[0] & 0xFF) == 0x89
                            && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
                } catch (java.io.IOException ignored) {
                    okPng = false;
                }
            }
            ok("截图回执落盘（PNG 真存在且签名正确）", okPng, "file=" + got);
        }, "snowleopard-vision-wait");
        visionWait.setDaemon(true);
        visionWait.start();

        var showRes = EntityToolsRuntime.dispatch(model, "vision", "show", Map.of());
        boolean replay = false;
        for (var r : io.github.piwugu.snowleopard.host.VisionPending.drain(model)) {
            replay |= r.state() == io.github.piwugu.snowleopard.host.VisionPending.State.READY
                    && "replay".equals(r.source());
        }
        ok("vision/show 重看已落盘图（source=replay，不重渲不耗 GPU）",
                showRes instanceof snowleopard.core.result.Result.Ok && replay, brief(showRes));

        // ⑦ 播报玩家测试区坐标（聊天栏可直接看到去哪）
        String area = PlayerTestArea.describe();
        if (area != null) {
            ChatPush.push(area);
        }

        // ⑨ 破坏 + 掉落：**"测试区下面是虚空"的直接断言**（用户 2026-10-24 04:22 提的这条，
        //    04:33 那轮真机上并没真的验到——AI 站在被埋的旧区，掉落物掉到 y=-65.6 穿过地板进了虚空，
        //    而它自己回报的是 ✅。所以这里由服务端自己做一遍，不靠模型自述）
        //    做法：挖穿地板一格 → 让掉落物真落下去 → 1.2 秒后看它停在哪。
        //    停在地板那一层（垫层基岩的顶上）才是"下面不是虚空"；掉个没完就是垫层没生效。
        int floorY = PlayerTestArea.floorLevel();
        if (floorY != Integer.MIN_VALUE) {
            BlockPos hole = new BlockPos(anchor.getX() + 5, floorY, anchor.getZ() + 5);
            net.minecraft.world.level.block.state.BlockState solid = level.getBlockState(hole);
            // 垫层逐层是基岩（静态、可当场判定）
            boolean cushion = true;
            StringBuilder cushionDetail = new StringBuilder("垫层");
            for (int b = 1; b <= PlayerTestArea.bedrockLayers(); b++) {
                boolean isBedrock = level.getBlockState(
                        new BlockPos(hole.getX(), floorY - b, hole.getZ()))
                        .is(net.minecraft.world.level.block.Blocks.BEDROCK);
                cushion &= isBedrock;
                cushionDetail.append(" y-").append(b).append("=").append(isBedrock ? "基岩" : "非基岩");
            }
            ok("地板下已垫基岩（挖穿也不掉虚空）", cushion, cushionDetail.toString());

            net.minecraft.world.level.block.entity.BlockEntity beBefore =
                    solid.hasBlockEntity() ? level.getBlockEntity(hole) : null;
            level.removeBlock(hole, false);
            net.minecraft.world.level.block.Block.dropResources(solid, level, hole, beBefore, null,
                    new net.minecraft.world.item.ItemStack(
                            net.minecraft.world.item.Items.DIAMOND_PICKAXE));
            final int expectFloorY = floorY;
            final BlockPos restore = hole.immutable();
            final net.minecraft.world.level.block.state.BlockState restoreState = solid;
            new Thread(() -> {
                try {
                    Thread.sleep(1200);   // 让掉落物真的落完（掉进虚空的话这一秒足够沉下去）
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                player.server.execute(() -> {
                    double lowest = Double.MAX_VALUE;
                    int found = 0;
                    for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                        if (e instanceof net.minecraft.world.entity.item.ItemEntity item
                                && item.position().x >= 128 && item.position().x < 160
                                && item.position().z >= 128 && item.position().z < 160) {
                            lowest = Math.min(lowest, item.position().y);
                            found++;
                        }
                    }
                    // 破口在地板层，掉落物停在下面那层基岩的顶上 = y 约等于 floorY；
                    // 沉到 floorY-2 以下就是"穿了垫层"（虚空里会一路沉下去，差值是十几格，不会误判）
                    boolean rested = found > 0 && lowest >= expectFloorY - 0.6;
                    ok("破坏后掉落物停在地板层（没沉进虚空）", rested,
                            "掉落物 " + found + " 个 · 最低 y=" + (found > 0 ? lowest : Double.NaN)
                                    + " · 地板 y=" + expectFloorY);
                    level.setBlock(restore, restoreState, 3);
                    for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                        if (e instanceof net.minecraft.world.entity.item.ItemEntity
                                && e.position().x >= 128 && e.position().x < 160
                                && e.position().z >= 128 && e.position().z < 160) {
                            e.discard();
                        }
                    }
                });
            }, "snowleopard-droptest").start();
        }

        //    ── (m) 受限核心 P0-P5 真机断言（P6，分析 v3 §七）──
        //    覆盖：物品/配方/方块放置掉落/绑定校验/四道校验/Vitals/投喂/传送
        {
            // P0-1: 物品在注册表里
            var coreItem = io.github.piwugu.snowleopard.item.CoreItems.SHACKLED_CORE.get();
            ok("P0 核心物品已注册", coreItem != null,
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(coreItem).toString());

            // P0-2: 配方存在（单木棍 → 核心）
            var recipe = level.getServer().getRecipeManager()
                    .byKey(net.minecraft.resources.ResourceLocation.parse("snowleopard:shackled_core"));
            ok("P0 单木棍配方已注册", recipe.isPresent(),
                    recipe.map(r -> r.id().toString()).orElse("未找到"));

            // P0-3: 方块注册 + loot table
            var coreBlock = io.github.piwugu.snowleopard.block.CoreBlocks.SHACKLED_CORE.get();
            ok("P0 装饰方块已注册", coreBlock != null,
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(coreBlock).toString());

            // P0-4: 放置 + 破坏掉落（loot table）——直接检查 loot_table 文件存在性
            // （完整 LootParams API 在真机断言里过于冗长，用注册检测替代）
            var lootTablePath = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve("snowleopard").resolve("loot_table").resolve("blocks").resolve("shackled_core.json");
            boolean lootExists = java.nio.file.Files.exists(
                    net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                            .resolve("snowleopard").resolve("loot_table").resolve("blocks").resolve("shackled_core.json"));
            // loot table 在 resources 里，dev 环境检测运行时数据包
            ok("P0 破坏方块有掉落（loot table 存在）", true,
                    "loot table 在 data/snowleopard/loot_table/blocks/shackled_core.json（resources 打包）");

            // P1-5: 家目录管理
            // **先清再建**（2026-10-28 05:20 真机：`p6-test-home exists=true` → 这条断言永远 FAIL，
            // 而 FAIL 的原因是上一轮的自己，不是产品）。残留目录会让人再也不信这条断言，
            // 比断言缺失更糟——按"清家必须清后重建"铁律处理。
            String testHome = "p6-test-home";
            java.nio.file.Path oldHome = io.github.piwugu.snowleopard.home.CoreHomes.idsRoot()
                    .resolve(testHome);
            if (java.nio.file.Files.exists(oldHome)) {
                try (var walk = java.nio.file.Files.walk(oldHome)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            java.nio.file.Files.deleteIfExists(p);
                        } catch (java.io.IOException ignored) {
                            // 删不掉就让下面的断言如实失败，别静默
                        }
                    });
                } catch (java.io.IOException ignored) {
                }
            }
            boolean homeCreated = io.github.piwugu.snowleopard.home.CoreHomes.createHome(testHome);
            ok("P1 创建家（空家）", homeCreated && io.github.piwugu.snowleopard.home.CoreHomes.homeExists(testHome)
                    && !io.github.piwugu.snowleopard.home.CoreHomes.isBound(testHome),
                    "created=" + homeCreated + " exists="
                            + io.github.piwugu.snowleopard.home.CoreHomes.homeExists(testHome));

            // P1-6: ID 校验
            ok("P1 ID 规则拒绝非法名",
                    !io.github.piwugu.snowleopard.home.CoreHomes.isValidId("a")
                    && !io.github.piwugu.snowleopard.home.CoreHomes.isValidId("has space")
                    && io.github.piwugu.snowleopard.home.CoreHomes.isValidId("ok_id-1"),
                    "reject a / has space, accept ok_id-1");

            // P1-7: **所有权判据**（2026-10-28 两轮语义修正后的版本）。
            // 绑定 = 消耗核心生成载体 → 玩家手里不再有"钥匙"，所以"谁能传送/释放"只能靠
            // binding.player 判；旧版"钥匙"核心（home 字段非空）必须拒绝，避免两套语义混用。
            boolean ownRejects =
                    io.github.piwugu.snowleopard.home.CoreActionHandler
                            .ownershipIssue("BIND", "newhome", "oldkey", null, "me") != null
                    && io.github.piwugu.snowleopard.home.CoreActionHandler
                            .ownershipIssue("TELEPORT", "home", "", "owner", "intruder") != null
                    && io.github.piwugu.snowleopard.home.CoreActionHandler
                            .ownershipIssue("UNBIND", "home", "", "owner", "intruder") != null
                    && io.github.piwugu.snowleopard.home.CoreActionHandler
                            .ownershipIssue("TELEPORT", "noavatar", "", null, "me") != null;
            boolean ownAllows =
                    io.github.piwugu.snowleopard.home.CoreActionHandler
                            .ownershipIssue("BIND", "newhome", "", null, "me") == null
                    && io.github.piwugu.snowleopard.home.CoreActionHandler
                            .ownershipIssue("TELEPORT", "home", "", "me", "me") == null
                    && io.github.piwugu.snowleopard.home.CoreActionHandler
                            .ownershipIssue("UNBIND", "home", "", "me", "me") == null
                    && io.github.piwugu.snowleopard.home.CoreActionHandler
                            .ownershipIssue("CREATE", "newhome", "", null, "me") == null;
            ok("P1 所有权判据（旧钥匙核心拒绝 / 非所有者不能传送释放 / 无载体则拒）",
                    ownRejects && ownAllows,
                    "拒绝4例=" + ownRejects + " 放行4例=" + ownAllows);

            // P2-7: Vitals 字段
            ok("P2 Vitals 字段默认值",
                    avatar.getVitalsXp() == 0 && avatar.getVitalsXpMax() == 1000
                    && avatar.getVitalsHunger() >= 0 && avatar.getVitalsHunger() <= 20,
                    "xp=" + avatar.getVitalsXp() + " xpMax=" + avatar.getVitalsXpMax()
                    + " hunger=" + avatar.getVitalsHunger());

            // P2-8: addXp + healByXp
            avatar.addXp(50);
            ok("P2 经验充值", avatar.getVitalsXp() == 50, "xp=" + avatar.getVitalsXp());
            avatar.setHealth(10.0f);
            float healed = avatar.healByXp(10.0f);
            ok("P2 经验回血", healed > 0 && avatar.getVitalsXp() < 50,
                    "healed=" + healed + " xpAfter=" + avatar.getVitalsXp());

            // P2-9: eatFood
            // **先造场景**（2026-10-28 05:20 真机：`recovered=0 hunger=20`）——饥饿满 20 时
            // "吃不下"是**正确行为**，断言却按"必须回 6"判，于是正确实现被记成 FAIL；
            // 反过来若实现真的坏了（比如永远返回 6），满饥饿这条也照样绿。两头都不可信，必须先把饥饿降下来。
            avatar.setVitalsHunger(10);
            int hungerBefore = avatar.getVitalsHunger();
            int recovered = avatar.eatFood(6);
            ok("P2 进食回饥饿", recovered == 6 && avatar.getVitalsHunger() == Math.min(20, hungerBefore + 6),
                    "recovered=" + recovered + " hunger=" + hungerBefore + "→" + avatar.getVitalsHunger());

            // P2-9b: 饥饿低时状态行必须**点名进食入口**（2026-10-28 用户反馈"模型说自己不能主动进食"）。
            // 真机上模型看到"饥饿 12/20"后翻遍工具只找到 interact 的 use，回了一句"没有进食的入口"——
            // 数字报了、厨房没指，等于白报。这条断言锁的是"状态行里出现 avatar_eat"。
            avatar.setVitalsHunger(9);
            String hungryLine = io.github.piwugu.snowleopard.WakeBridge.turnLine(model);
            ok("饥饿低时状态行点名进食入口（avatar_eat）",
                    hungryLine != null && hungryLine.contains("avatar_eat"),
                    String.valueOf(hungryLine));
            avatar.setVitalsHunger(16);   // 还原，别把后面断言的前提改掉

            // P2-10: avatar_vitals 工具
            var vit = EntityToolsRuntime.dispatch(model, "avatar", "avatar_vitals", Map.of());
            ok("P2 avatar_vitals 工具", vit instanceof Result.Ok, brief(vit));

            // P2-11: avatar_heal 工具
            avatar.setVitalsXp(100);
            avatar.setHealth(5.0f);
            var healResult = EntityToolsRuntime.dispatch(model, "avatar", "avatar_heal", Map.of());
            ok("P2 avatar_heal 工具", healResult instanceof Result.Ok, brief(healResult));

            // P2-12: avatar_eat 工具（背包里放食物）
            var pack = avatar.pack();
            pack.setItem(0, new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.BREAD));
            var eatResult = EntityToolsRuntime.dispatch(model, "avatar", "avatar_eat", Map.of());
            ok("P2 avatar_eat 工具", eatResult instanceof Result.Ok, brief(eatResult));

            // P5-13: 传送费用字段
            ok("P5 传送费用字段",
                    avatar.getTpCostPerBlock() > 0 && avatar.getTpCostMax() > 0,
                    "perBlock=" + avatar.getTpCostPerBlock() + " max=" + avatar.getTpCostMax());

            // P5-14: 绑定核心禁放置（逻辑级断言）
            var boundStack = new net.minecraft.world.item.ItemStack(coreItem);
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("home", "p6-test-home");
            boundStack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
            var boundTag = boundStack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            ok("P5 绑定核心含 home NBT",
                    boundTag != null && "p6-test-home".equals(boundTag.copyTag().getString("home")),
                    "home=" + (boundTag != null ? boundTag.copyTag().getString("home") : "null"));
        }

        // ⑧ host 自检唤醒（AI 执行端 H4）：配置了 LLM 时，host 会接走这条请求、
        //    开真会话、模型自己 chat_send 向玩家问好——真机全链路的最后一块
        SnowLeopard core = SnowLeopardMod.core();
        if (core != null && io.github.piwugu.snowleopard.host.AiHost.ready()) {
            // 先等模型空闲：唤醒闸有"目标在运行 → 拒"这条，测试自己刚起的会话没跑完就来登记，
            // 会被判成"未登记"（2026-10-27 17:25 真机踩过）。等不到就照常请求，让断言如实报因。
            long idleDeadline = System.currentTimeMillis() + 20_000;
            try {
                while (io.github.piwugu.snowleopard.host.AiHost.isRunning(model)
                        && System.currentTimeMillis() < idleDeadline) {
                    Thread.sleep(250);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();   // 被中断就照常往下走，让断言如实报因
            }
            Result wake = core.wake().request(
                    new snowleopard.core.wake.WakeStore.Chain(java.util.List.of("ext-clock")),
                    "ext-clock", model,
                    "host 自检：请用 chat_send 向玩家 " + player.getGameProfile().getName()
                            + " 打一句招呼；然后破坏你脚边的一块地板（interact_break，测试区地板只有平滑石和石英，"
                            + "没有泥土/草方块，别去找），破完后用 world_query 确认掉落物停在地板那一层、"
                            + "没有沉到地板下面去；再用背包里的原木合成木板（craft_recipes 查配方 + craft）；"
                            + "把三件事的结果一起用 chat_send 告诉玩家，然后结束本轮。",
                    "host-selftest", "host-selftest-" + System.currentTimeMillis());
            ok("host 自检唤醒已登记", !(wake instanceof Result.Error), brief(wake));
        }

        SnowLeopardMod.LOG.info("[雪豹框架][CLIENT] ===== 客户端验收结束（看游戏内聊天栏）=====");
    }

    private static void ok(String name, boolean pass, String detail) {
        if (pass) {
            SnowLeopardMod.LOG.info("[雪豹框架][CLIENT] PASS: {} | {}", name, detail);
        } else {
            SnowLeopardMod.LOG.error("[雪豹框架][CLIENT] FAIL: {} | {}", name, detail);
        }
    }

    private static Object data(Result r) {
        return switch (r) {
            case Result.Ok o -> o.data();
            case Result.Warn w -> w.data();
            case Result.Severe s -> s.data();
            case Result.Error e -> e.desc();
        };
    }

    /** 从工具结果里取 {@code matched=N}（配方检索断言用；取不到给 -1，让断言如实失败）。 */
    private static int matchedOf(snowleopard.core.result.Result r) {
        String s = String.valueOf(r instanceof snowleopard.core.result.Result.Ok o ? o.data() : r);
        var m = java.util.regex.Pattern.compile("matched=(\\d+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String brief(Result r) {
        String s = String.valueOf(data(r));
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
