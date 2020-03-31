package bq_standard.tasks;

import betterquesting.api.api.ApiReference;
import betterquesting.api.api.QuestingAPI;
import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.party.IParty;
import betterquesting.api.questing.tasks.IProgression;
import betterquesting.api.questing.tasks.ITask;
import betterquesting.api.utils.BigItemStack;
import betterquesting.api.utils.ItemComparison;
import betterquesting.api2.cache.QuestCache;
import betterquesting.api2.client.gui.misc.IGuiRect;
import betterquesting.api2.client.gui.panels.IGuiPanel;
import betterquesting.api2.storage.DBEntry;
import bq_standard.NbtBlockType;
import bq_standard.client.gui.tasks.PanelTaskInteractItem;
import bq_standard.core.BQ_Standard;
import bq_standard.tasks.factory.FactoryTaskInteractItem;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;

public class TaskInteractItem implements ITask, IProgression<Integer>
{
	private final List<UUID> completeUsers = new ArrayList<>();
	private final HashMap<UUID, Integer> userProgress = new HashMap<>();
	
	@Nullable
    public BigItemStack targetItem = null;
    public final NbtBlockType targetBlock = new NbtBlockType(null);
	public boolean partialMatch = true;
	public boolean ignoreNBT = true;
	public boolean onInteract = true;
	public boolean onHit = false;
	public int required = 1;
    
    @Override
    public String getUnlocalisedName()
    {
        return BQ_Standard.MODID + ".task.interact_item";
    }
    
    @Override
    public ResourceLocation getFactoryID()
    {
        return FactoryTaskInteractItem.INSTANCE.getRegistryName();
    }
    
    public void onInteract(DBEntry<IQuest> quest, EntityPlayer player, ItemStack item, Block block, int meta, int x, int y, int z, boolean isHit)
    {
        UUID playerID = QuestingAPI.getQuestingUUID(player);
        if(isComplete(playerID)) return;
        
        if((!onHit && isHit) || (!onInteract && !isHit)) return;
        
        if(targetBlock.b != Blocks.air && targetBlock.b != null)
        {
            if(block == Blocks.air || block == null) return;
            TileEntity tile = block.hasTileEntity(meta) ? player.worldObj.getTileEntity(x, y, z) : null;
            NBTTagCompound tags = null;
            if(tile != null)
            {
                tags = new NBTTagCompound();
                tile.writeToNBT(tags);
            }
            
            int tmpMeta = (targetBlock.m < 0 || targetBlock.m == OreDictionary.WILDCARD_VALUE)? OreDictionary.WILDCARD_VALUE : meta;
            boolean oreMatch = targetBlock.oreDict.length() > 0 && OreDictionary.getOres(targetBlock.oreDict).contains(new ItemStack(block, 1, tmpMeta));
    
            if((!oreMatch && (block != targetBlock.b || (targetBlock.m >= 0 && meta != targetBlock.m))) || !ItemComparison.CompareNBTTag(targetBlock.tags, tags, true))
            {
                return;
            }
        }
        
        if(targetItem != null)
        {
            if(targetItem.hasOreDict() && !ItemComparison.OreDictionaryMatch(targetItem.getOreIngredient(), targetItem.GetTagCompound(), item, !ignoreNBT, partialMatch))
            {
                return;
            } else if(!ItemComparison.StackMatch(targetItem.getBaseStack(), item, !ignoreNBT, partialMatch))
            {
                return;
            }
        }
        
        int progress = getUsersProgress(playerID);
        setUserProgress(playerID, ++progress);
        QuestCache qc = (QuestCache)player.getExtendedProperties(QuestCache.LOC_QUEST_CACHE.toString());
        if(qc != null) qc.markQuestDirty(quest.getID());
        
        detect(player, quest.getValue());
    }
    
    @Override
    public void detect(EntityPlayer player, IQuest quest)
    {
        UUID playerID = QuestingAPI.getQuestingUUID(player);
        if(isComplete(playerID)) return;
        
        if(getUsersProgress(playerID) >= required)
        {
            setComplete(playerID);
            QuestCache qc = (QuestCache)player.getExtendedProperties(QuestCache.LOC_QUEST_CACHE.toString());
            if(qc != null) qc.markQuestDirty(QuestingAPI.getAPI(ApiReference.QUEST_DB).getID(quest));
        }
    }
	
	@Override
	public boolean isComplete(UUID uuid)
	{
		return completeUsers.contains(uuid);
	}
	
	@Override
	public void setComplete(UUID uuid)
	{
		if(!completeUsers.contains(uuid))
		{
			completeUsers.add(uuid);
		}
	}

	@Override
	public void resetUser(UUID uuid)
	{
		completeUsers.remove(uuid);
		userProgress.remove(uuid);
	}

	@Override
	public void resetAll()
	{
		completeUsers.clear();
		userProgress.clear();
	}
    
    @Override
	@SideOnly(Side.CLIENT)
    public IGuiPanel getTaskGui(IGuiRect rect, IQuest quest)
    {
        return new PanelTaskInteractItem(rect, quest, this);
    }
    
    @Override
    @Nullable
	@SideOnly(Side.CLIENT)
    public GuiScreen getTaskEditor(GuiScreen parent, IQuest quest)
    {
        return null;
    }
	
	@Override
	public void readProgressFromNBT(NBTTagCompound json, boolean merge)
	{
		completeUsers.clear();
		NBTTagList cList = json.getTagList("completeUsers", 8);
		for(int i = 0; i < cList.tagCount(); i++)
		{
			
			try
			{
				completeUsers.add(UUID.fromString(cList.getStringTagAt(i)));
			} catch(Exception e)
			{
				BQ_Standard.logger.log(Level.ERROR, "Unable to load UUID for task", e);
			}
		}
		
		userProgress.clear();
		NBTTagList pList = json.getTagList("userProgress", 10);
		for(int i = 0; i < pList.tagCount(); i++)
		{
			NBTTagCompound pTag = pList.getCompoundTagAt(i);
			UUID uuid;
			try
			{
				uuid = UUID.fromString(pTag.getString("uuid"));
			} catch(Exception e)
			{
				BQ_Standard.logger.log(Level.ERROR, "Unable to load user progress for task", e);
				continue;
			}
			
			userProgress.put(uuid, pTag.getInteger("value"));
		}
	}
	
	@Override
	public NBTTagCompound writeProgressToNBT(NBTTagCompound json, List<UUID> users)
	{
		NBTTagList jArray = new NBTTagList();
		for(UUID uuid : completeUsers)
		{
			jArray.appendTag(new NBTTagString(uuid.toString()));
		}
		json.setTag("completeUsers", jArray);
		
		NBTTagList progArray = new NBTTagList();
		for(Entry<UUID,Integer> entry : userProgress.entrySet())
		{
			NBTTagCompound pJson = new NBTTagCompound();
			pJson.setString("uuid", entry.getKey().toString());
			pJson.setInteger("value", entry.getValue());
			progArray.appendTag(pJson);
		}
		json.setTag("userProgress", progArray);
		
		return json;
	}
    
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt)
    {
        nbt.setTag("item", targetItem != null ? targetItem.writeToNBT(new NBTTagCompound()) : new NBTTagCompound());
        nbt.setTag("block", targetBlock.writeToNBT(new NBTTagCompound()));
        nbt.setBoolean("ignoreNbt", ignoreNBT);
        nbt.setBoolean("partialMatch", partialMatch);
        nbt.setInteger("requiredUses", required);
        nbt.setBoolean("onInteract", onInteract);
        nbt.setBoolean("onHit", onHit);
        return nbt;
    }
    
    @Override
    public void readFromNBT(NBTTagCompound nbt)
    {
        targetItem = BigItemStack.loadItemStackFromNBT(nbt.getCompoundTag("item"));
        targetBlock.readFromNBT(nbt.getCompoundTag("block"));
        ignoreNBT = nbt.getBoolean("ignoreNbt");
        partialMatch = nbt.getBoolean("partialMatch");
        required = nbt.getInteger("requiredUses");
        onInteract = nbt.getBoolean("onInteract");
        onHit = nbt.getBoolean("onHit");
    }
	
	@Override
	public void setUserProgress(UUID uuid, Integer progress)
	{
		userProgress.put(uuid, progress);
	}
	
	@Override
	public Integer getUsersProgress(UUID... users)
	{
		int i = 0;
		
		for(UUID uuid : users)
		{
			Integer n = userProgress.get(uuid);
			i += n == null? 0 : n;
		}
		
		return i;
	}
	
	public Integer getPartyProgress(UUID uuid)
	{
		IParty party = QuestingAPI.getAPI(ApiReference.PARTY_DB).getUserParty(uuid);
        return getUsersProgress(party == null ? new UUID[]{uuid} : party.getMembers().toArray(new UUID[0]));
	}
	
	@Override
	public Integer getGlobalProgress()
	{
		int total = 0;
		
		for(Integer i : userProgress.values())
		{
			total += i == null? 0 : i;
		}
		
		return total;
	}
    
    @Override
    public float getParticipation(UUID uuid)
    {
		if(required <= 0)
		{
			return 1F;
		}
		
		return getUsersProgress(uuid) / (float)required;
    }
}
