package emu.grasscutter.game.gacha;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.reflect.TypeToken;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GenshinData;
import emu.grasscutter.data.def.ItemData;
import emu.grasscutter.game.GenshinPlayer;
import emu.grasscutter.game.avatar.GenshinAvatar;
import emu.grasscutter.game.inventory.GenshinItem;
import emu.grasscutter.game.inventory.ItemType;
import emu.grasscutter.game.inventory.MaterialType;
import emu.grasscutter.net.proto.GachaItemOuterClass.GachaItem;
import emu.grasscutter.net.proto.GachaTransferItemOuterClass.GachaTransferItem;
import emu.grasscutter.net.proto.GetGachaInfoRspOuterClass.GetGachaInfoRsp;
import emu.grasscutter.net.proto.ItemParamOuterClass.ItemParam;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.packet.send.PacketDoGachaRsp;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

public class GachaManager {
	private final GameServer server;
	private final Int2ObjectMap<GachaBanner> gachaBanners;
	private GetGachaInfoRsp cachedProto;
	
	private int[] yellowAvatars = new int[] {1003, 1016, 1042, 1035, 1041};
	private int[] yellowWeapons = new int[] {11501, 11502, 12501, 12502, 13502, 13505, 14501, 14502, 15501, 15502};
	private int[] purpleAvatars = new int[] {1006, 1014, 1015, 1020, 1021, 1023, 1024, 1025, 1027, 1031, 1032, 1034, 1036, 1039, 1043, 1044, 1045, 1048, 1053, 1055, 1056, 1064};
	private int[] purpleWeapons = new int[] {11401, 11402, 11403, 11405, 12401, 12402, 12403, 12405, 13401, 13407, 14401, 14402, 14403, 14409, 15401, 15402, 15403, 15405};
	private int[] blueWeapons = new int[] {11301, 11302, 11306, 12301, 12302, 12305, 13303, 14301, 14302, 14304, 15301, 15302, 15304};
	
	private static int starglitterId = 221;
	private static int stardustId = 222;
	
	public GachaManager(GameServer server) {
		this.server = server;
		this.gachaBanners = new Int2ObjectOpenHashMap<>();
		this.load();
	}

	public GameServer getServer() {
		return server;
	}

	public Int2ObjectMap<GachaBanner> getGachaBanners() {
		return gachaBanners;
	}
	
	public int randomRange(int min, int max) {
		return ThreadLocalRandom.current().nextInt(max - min + 1) + min;
	}
	
	public int getRandom(int[] array) {
		return array[randomRange(0, array.length - 1)];
	}
	
	public synchronized void load() {
		try (FileReader fileReader = new FileReader(Grasscutter.getConfig().DATA_FOLDER + "Banners.json")) {
			List<GachaBanner> banners = Grasscutter.getGsonFactory().fromJson(fileReader, TypeToken.getParameterized(Collection.class, GachaBanner.class).getType());
			for (GachaBanner banner : banners) {
				getGachaBanners().put(banner.getGachaType(), banner);
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public synchronized void doPulls(GenshinPlayer player, int gachaType, int times) {
		// Sanity check
		if (times != 10 && times != 1) {
			return;
		} 
		if (player.getInventory().getInventoryTab(ItemType.ITEM_WEAPON).getSize() + times > player.getInventory().getInventoryTab(ItemType.ITEM_WEAPON).getMaxCapacity()) {
			player.sendPacket(new PacketDoGachaRsp());
			return;
		}
		
		// Get banner
		GachaBanner banner = this.getGachaBanners().get(gachaType);
		if (banner == null) {
			player.sendPacket(new PacketDoGachaRsp());
			return;
		}

		// Spend currency
		if (banner.getCostItem() > 0) {
			GenshinItem costItem = player.getInventory().getInventoryTab(ItemType.ITEM_MATERIAL).getItemById(banner.getCostItem());
			if (costItem == null || costItem.getCount() < times) {
				return;
			}
			
			player.getInventory().removeItem(costItem, times);
		}
		
		// Roll
		PlayerGachaBannerInfo gachaInfo = player.getGachaInfo().getBannerInfo(banner);
		IntList wonItems = new IntArrayList(times);
		
		for (int i = 0; i < times; i++) {
			int random = this.randomRange(1, 10000);
			int itemId = 0;
			
			int bonusYellowChance = gachaInfo.getPity5() >= banner.getSoftPity() ? 100 * (gachaInfo.getPity5() - banner.getSoftPity() - 1): 0;
			int yellowChance = 60 + (int) Math.floor(100f * (gachaInfo.getPity5() / (banner.getSoftPity() - 1D))) + bonusYellowChance;
			int purpleChance = 10000 - (510 + (int) Math.floor(790f * (gachaInfo.getPity4() / 8f)));
		
			if (random <= yellowChance || gachaInfo.getPity5() >= banner.getHardPity()) {
				if (banner.getRateUpItems1().length > 0) {
					int eventChance = this.randomRange(1, 100);
					
					if (eventChance >= banner.getEventChance() || gachaInfo.getFailedFeaturedItemPulls() >= 1) {
						itemId = getRandom(banner.getRateUpItems1());
						gachaInfo.setFailedFeaturedItemPulls(0);
					} else {
						// Lost the 50/50... rip
						gachaInfo.addFailedFeaturedItemPulls(1);
					}
				}
				
				if (itemId == 0) {
					int typeChance = this.randomRange(banner.getMinItemType(), banner.getMaxItemType());
					if (typeChance == 1) {
						itemId = getRandom(this.yellowAvatars);
					} else {
						itemId = getRandom(this.yellowWeapons);
					}
				}

				// Pity
				gachaInfo.addPity4(1);
				gachaInfo.setPity5(0);
			} else if (random >= purpleChance || gachaInfo.getPity4() >= 9) {
				if (banner.getRateUpItems2().length > 0) {
					int eventChance = this.randomRange(1, 100);
					
					if (eventChance >= 50) {
						itemId = getRandom(banner.getRateUpItems2());
					}
				}
				
				if (itemId == 0) {
					int typeChance = this.randomRange(banner.getMinItemType(), banner.getMaxItemType());
					if (typeChance == 1) {
						itemId = getRandom(this.purpleAvatars);
					} else {
						itemId = getRandom(this.purpleWeapons);
					}
				}
				
				// Pity
				gachaInfo.addPity5(1);
				gachaInfo.setPity4(0);
			} else {
				itemId = getRandom(this.blueWeapons);
				
				// Pity
				gachaInfo.addPity4(1);
				gachaInfo.addPity5(1);
			}
			
			// Add winning item
			wonItems.add(itemId);
		}
		
		// Add to character
		List<GachaItem> list = new ArrayList<>();
		int stardust = 0, starglitter = 0;
		
		for (int itemId : wonItems) {
			ItemData itemData = GenshinData.getItemDataMap().get(itemId);
			if (itemData == null) {
				continue;
			}
			
			// Create gacha item
			GachaItem.Builder gachaItem = GachaItem.newBuilder();
			int addStardust = 0, addStarglitter = 0;
			boolean isTransferItem = false;
			
			// Const check
			if (itemData.getMaterialType() == MaterialType.MATERIAL_AVATAR) {
				int avatarId = (itemData.getId() % 1000) + 10000000;
				GenshinAvatar avatar = player.getAvatars().getAvatarById(avatarId);
				if (avatar != null) {
					int constLevel = avatar.getCoreProudSkillLevel();
					int constItemId = itemData.getId() + 100;
					GenshinItem constItem = player.getInventory().getInventoryTab(ItemType.ITEM_MATERIAL).getItemById(constItemId);
					if (constItem != null) {
						constLevel += constItem.getCount();
					}
					
					if (constLevel < 6) {
						// Not max const
						addStarglitter = 2;
						// Add 1 const
						gachaItem.addTransferItems(GachaTransferItem.newBuilder().setItem(ItemParam.newBuilder().setItemId(constItemId).setCount(1)).setIsTransferItemNew(constItem == null));
						gachaItem.addTokenItemList(ItemParam.newBuilder().setItemId(constItemId).setCount(1));
						player.getInventory().addItem(constItemId, 1);
					} else {
						// Is max const
						addStarglitter = 5;
					}
					
					if (itemData.getRankLevel() == 5) {
						addStarglitter *= 5;
					}
					
					isTransferItem = true;
				} else {
					// New
					gachaItem.setIsGachaItemNew(true);
				}
			} else {
				// Is weapon
				switch (itemData.getRankLevel()) {
					case 5:
						addStarglitter = 10;
						break;
					case 4:
						addStarglitter = 2;
						break;
					case 3:
						addStardust = 15;
						break;
				}
			}

			// Create item
			GenshinItem item = new GenshinItem(itemData);
			gachaItem.setGachaItem(item.toItemParam());
			player.getInventory().addItem(item);
			
			stardust += addStardust;
			starglitter += addStarglitter;
			
			if (addStardust > 0) {
				gachaItem.addTokenItemList(ItemParam.newBuilder().setItemId(stardustId).setCount(addStardust));
			} if (addStarglitter > 0) {
				ItemParam starglitterParam = ItemParam.newBuilder().setItemId(starglitterId).setCount(addStarglitter).build();
				if (isTransferItem) {
					gachaItem.addTransferItems(GachaTransferItem.newBuilder().setItem(starglitterParam));
				}
				gachaItem.addTokenItemList(starglitterParam);
			}
			
			list.add(gachaItem.build());
		}
		
		// Add stardust/starglitter
		if (stardust > 0) {
			player.getInventory().addItem(stardustId, stardust);
		} if (starglitter > 0) {
			player.getInventory().addItem(starglitterId, starglitter);
		}
		
		// Packets
		player.sendPacket(new PacketDoGachaRsp(banner, list));
	}
	
	private synchronized GetGachaInfoRsp createProto() {
		GetGachaInfoRsp.Builder proto = GetGachaInfoRsp.newBuilder().setGachaRandom(12345);
		
		for (GachaBanner banner : getGachaBanners().values()) {
			proto.addGachaInfoList(banner.toProto());
		}
				
		return proto.build();
	}
	
	public GetGachaInfoRsp toProto() {
		if (this.cachedProto == null) {
			this.cachedProto = createProto();
		}
		
		return this.cachedProto;
	}
}
