package top.theillusivec4.champions.common.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.champions.api.IChampion;
import top.theillusivec4.champions.common.capability.ChampionCapability;
import top.theillusivec4.champions.common.config.ChampionsConfig;
import top.theillusivec4.champions.common.config.ConfigEnums.Permission;
import top.theillusivec4.champions.common.rank.Rank;
import top.theillusivec4.champions.common.registry.ModEntityTypes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static top.theillusivec4.champions.common.integration.gamestages.GameStagesPlugin.hasEntityStage;

public class ChampionHelper {

	private static final Map<ResourceKey<Level>, Set<BlockPos>> BEACON_POS = new HashMap<>();

	private static MinecraftServer server = null;

	/**
	 * check entity is LivingEntity & Enemy
	 */
	public static boolean isValidChampionEntity(final Entity entity) {
		boolean isEligible = false;
		if (entity instanceof LivingEntity livingEntity) {
			if (ChampionsConfig.allowChampionsList) {
				isEligible = isValidChampionEntityType(livingEntity.getType());
			} else {
				// If champions are not allowed, check if the entity is an enemy
				isEligible = livingEntity instanceof Enemy || livingEntity.getType().getCategory() == MobCategory.MONSTER;
			}
			if (isEligible) {
				isEligible = Utils.isGameStagesLoaded() ? hasEntityStage(livingEntity) : true;
			}
		}
		return isEligible; // If entity is not a LivingEntity, return false
	}

	public static boolean isValidChampionEntityType(final EntityType<?> entityType) {
		if (ChampionsConfig.allowChampionsList) {
			var isEligible = entityType.is(ModEntityTypes.Tags.ALLOW_CHAMPIONS);
			return ChampionsConfig.allowChampionsPermission == Permission.WHITELIST ? isEligible : !isEligible;
		}

		return entityType.getCategory() == MobCategory.MONSTER; // If entity is not a LivingEntity
	}

	/**
	 * @param client champion to check
	 * @return True if champion is valid Champion(Has ranks and affixes), else false.
	 */
	public static boolean isValidChampion(IChampion.Client client) {
		var rank = client.getRank();
		return rank.isPresent() && rank.map(Tuple::getA).orElse(0) > 0 && !client.getAffixes().isEmpty();
	}

	/**
	 * @param server champion to check
	 * @return True if champion is valid Champion(Has ranks and affixes), else false.
	 */
	public static boolean isValidChampion(IChampion.Server server) {
		var rank = server.getRank();
		return rank.isPresent() && rank.map(Rank::getTier).orElse(-1) > 0 && !server.getAffixes().isEmpty();
	}

	/**
	 * Check entity is champion (have affixes and rank)
	 *
	 * @param entity the entity to check
	 * @return true if entity is champion, false not champion
	 */
	public static boolean isChampionEntity(Entity entity) {
		return ChampionCapability.getCapability(entity).map(champion -> ChampionHelper.isValidChampion(champion.getServer())).orElse(false);
	}

	/**
	 * Check LivingEntity is potential champion entity.(can have data and spawn etc...)
	 *
	 * @param livingEntity that will check for.
	 * @return True if this is not potential champion, else false.
	 */
	public static boolean notPotential(final LivingEntity livingEntity) {
		return !isValidEntity(livingEntity) ||
				!isValidDimension(livingEntity.level().dimension().location()) ||
				nearActiveBeacon(livingEntity);
	}

	public static void addBeacon(Level level, BlockPos pos) {

		if (server != null && level != null && !level.isClientSide()) {
			BEACON_POS.computeIfAbsent(level.dimension(), key -> new HashSet<>()).add(pos.immutable());
		}
	}

	private static boolean isValidEntity(final LivingEntity livingEntity) {
		ResourceLocation rl = ForgeRegistries.ENTITY_TYPES.getDelegateOrThrow(livingEntity.getType()).key().location();

		String entity = rl.toString();

		if (ChampionsConfig.entitiesPermission == Permission.BLACKLIST) {
			return !ChampionsConfig.entitiesList.contains(entity);
		} else {
			return ChampionsConfig.entitiesList.contains(entity);
		}
	}

	public static boolean areEntitiesNearby(BlockPos pos, List<LivingEntity> livingEntities, EntityType<?> entityType) {
		for (LivingEntity livingentity : livingEntities) {
			if (livingentity.isAlive()
					&& !livingentity.isRemoved()
					&& pos.closerToCenterThan(livingentity.position(), 32.0)
					&& livingentity.getType() == entityType) {
				return true;
			}
		}

		return false;
	}

	private static boolean isValidDimension(final ResourceLocation resourceLocation) {
		String dimension = resourceLocation.toString();

		if (ChampionsConfig.dimensionPermission == Permission.BLACKLIST) {
			return !ChampionsConfig.dimensionList.contains(dimension);
		} else {
			return ChampionsConfig.dimensionList.contains(dimension);
		}
	}

	private static boolean nearActiveBeacon(final LivingEntity livingEntity) {
		int range = ChampionsConfig.beaconProtectionRange;

		if (range <= 0) {
			return false;
		}

		Level level = livingEntity.level();
		if (!(level instanceof ServerLevel serverLevel)) {
			return false;
		}
		Set<BlockPos> beaconPos = BEACON_POS.get(serverLevel.dimension());
		if (beaconPos == null || beaconPos.isEmpty()) {
			return false;
		}

		Set<BlockPos> toRemove = new HashSet<>();
		double rangeSq = (double) range * range;

		for (BlockPos pos : beaconPos) {
			if (livingEntity.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) > rangeSq) {
				continue;
			}
			LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(
					SectionPos.blockToSectionCoord(pos.getX()),
					SectionPos.blockToSectionCoord(pos.getZ())
			);
			if (chunk == null) {
				continue;
			}
			BlockEntity blockEntity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
			if (blockEntity instanceof BeaconBlockEntity beaconBlockEntity && !blockEntity.isRemoved()) {
				if (beaconBlockEntity.levels > 0) {
					return true;
				}
			} else {
				toRemove.add(pos);
			}
		}
		if (!toRemove.isEmpty()) {
			beaconPos.removeAll(toRemove);
			if (beaconPos.isEmpty()) {
				BEACON_POS.remove(serverLevel.dimension());
			}
		}

		return false;
	}

	public static void clearBeacons() {
		BEACON_POS.clear();
	}

	public static void setServer(MinecraftServer serverIn) {
		server = serverIn;
	}
}
