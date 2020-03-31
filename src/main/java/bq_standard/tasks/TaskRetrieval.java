package bq_standard.tasks;

import betterquesting.api.api.ApiReference;
import betterquesting.api.api.QuestingAPI;
import betterquesting.api.properties.NativeProps;
import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.party.IParty;
import betterquesting.api.questing.tasks.IItemTask;
import betterquesting.api.questing.tasks.IProgression;
import betterquesting.api.utils.BigItemStack;
import betterquesting.api.utils.ItemComparison;
import betterquesting.api.utils.JsonHelper;
import betterquesting.api.utils.NBTConverter;
import betterquesting.api2.cache.QuestCache;
import betterquesting.api2.client.gui.misc.IGuiRect;
import betterquesting.api2.client.gui.panels.IGuiPanel;
import betterquesting.api2.storage.DBEntry;
import bq_standard.client.gui.tasks.PanelTaskRetrieval;
import bq_standard.core.BQ_Standard;
import bq_standard.tasks.factory.FactoryTaskRetrieval;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.nbt.NBTBase.NBTPrimitive;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.Level;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.Map.Entry;

public class TaskRetrieval implements ITaskInventory, IProgression<int[]>, IItemTask
{
	private final List<UUID> completeUsers = new ArrayList<>();
	public final List<BigItemStack> requiredItems = new ArrayList<>();
	private final HashMap<UUID, int[]> userProgress = new HashMap<>();
	public boolean partialMatch = true;
	public boolean ignoreNBT = true;
	public boolean consume = false;
	public boolean groupDetect = false;
	public boolean autoConsume = false;
	
	@Override
	public String getUnlocalisedName()
	{
		return BQ_Standard.MODID + ".task.retrieval";
	}
	
	@Override
	public ResourceLocation getFactoryID()
	{
		return FactoryTaskRetrieval.INSTANCE.getRegistryName();
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
	public void onInventoryChange(@Nonnull DBEntry<IQuest> quest, @Nonnull EntityPlayer player)
    {
        if(!consume || autoConsume)
        {
            detect(player, quest.getValue());
        }
    }
    
	@Override
	public void detect(EntityPlayer player, IQuest quest)
	{
		UUID playerID = QuestingAPI.getQuestingUUID(player);
		
		if(player.inventory == null || isComplete(playerID)) return;
		
		int[] progress = this.getUsersProgress(playerID);
		boolean updated = false;
		
		if(!consume)
        {
            if(groupDetect) // Reset all detect progress
            {
                Arrays.fill(progress, 0);
            } else
            {
                for(int i = 0; i < progress.length; i++)
                {
                    if(progress[i] != 0 && progress[i] < requiredItems.get(i).stackSize) // Only reset progress for incomplete entries
                    {
                        progress[i] = 0;
                        updated = true;
                    }
                }
            }
        }
		
		for(int i = 0; i < player.inventory.getSizeInventory(); i++)
		{
            ItemStack stack = player.inventory.getStackInSlot(i);
            if(stack == null || stack.stackSize <= 0) continue;
            int remStack = stack.stackSize; // Allows the stack detection to split across multiple requirements
            
			for(int j = 0; j < requiredItems.size(); j++)
			{
				BigItemStack rStack = requiredItems.get(j);
				
				if(progress[j] >= rStack.stackSize) continue;
				
				int remaining = rStack.stackSize - progress[j];
				
				if(ItemComparison.StackMatch(rStack.getBaseStack(), stack, !ignoreNBT, partialMatch) || ItemComparison.OreDictionaryMatch(rStack.getOreIngredient(), rStack.GetTagCompound(), stack, !ignoreNBT, partialMatch))
				{
					if(consume)
					{
						ItemStack removed = player.inventory.decrStackSize(i, remaining);
						progress[j] += removed.stackSize;
					} else
					{
					    int temp = Math.min(remaining, remStack);
					    remStack -= temp;
						progress[j] += temp;
					}
					
					updated = true;
				}
			}
		}
		
		if(updated) setUserProgress(playerID, progress);
		
		boolean hasAll = true;
		int[] totalProgress = quest == null || !quest.getProperty(NativeProps.GLOBAL)? getPartyProgress(playerID) : getGlobalProgress();
		
		for(int j = 0; j < requiredItems.size(); j++)
		{
			BigItemStack rStack = requiredItems.get(j);
			
			if(totalProgress[j] >= rStack.stackSize) continue;
			
			hasAll = false;
			break;
		}
		
		if(hasAll)
		{
			setComplete(playerID);
			updated = true;
		}
		
		if(updated)
        {
            QuestCache qc = (QuestCache)player.getExtendedProperties(QuestCache.LOC_QUEST_CACHE.toString());
            if(qc != null) qc.markQuestDirty(QuestingAPI.getAPI(ApiReference.QUEST_DB).getID(quest));
        }
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound json)
	{
		json.setBoolean("partialMatch", partialMatch);
		json.setBoolean("ignoreNBT", ignoreNBT);
		json.setBoolean("consume", consume);
		json.setBoolean("groupDetect", groupDetect);
		json.setBoolean("autoConsume", autoConsume);
		
		NBTTagList itemArray = new NBTTagList();
		for(BigItemStack stack : this.requiredItems)
		{
			itemArray.appendTag(JsonHelper.ItemStackToJson(stack, new NBTTagCompound()));
		}
		json.setTag("requiredItems", itemArray);
		
		return json;
	}

	@Override
	public void readFromNBT(NBTTagCompound json)
	{
		partialMatch = json.getBoolean("partialMatch");
		ignoreNBT = json.getBoolean("ignoreNBT");
		consume = json.getBoolean("consume");
		groupDetect = json.getBoolean("groupDetect");
		autoConsume = json.getBoolean("autoConsume");
		
		requiredItems.clear();
		NBTTagList iList = json.getTagList("requiredItems", 10);
		for(int i = 0; i < iList.tagCount(); i++)
		{
			requiredItems.add(JsonHelper.JsonToItemStack(iList.getCompoundTagAt(i)));
		}
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
		
		for(int n = 0; n < pList.tagCount(); n++)
		{
			NBTTagCompound pTag = pList.getCompoundTagAt(n);
			UUID uuid;
			try
			{
				uuid = UUID.fromString(pTag.getString("uuid"));
			} catch(Exception e)
			{
				BQ_Standard.logger.log(Level.ERROR, "Unable to load user progress for task", e);
				continue;
			}
			
			int[] data = new int[requiredItems.size()];
			List<NBTBase> dJson = NBTConverter.getTagList(pTag.getTagList("data", 3));
			for(int i = 0; i < data.length && i < dJson.size(); i++)
			{
				try
				{
					data[i] = ((NBTPrimitive)dJson.get(i)).func_150287_d();
				} catch(Exception e)
				{
					BQ_Standard.logger.log(Level.ERROR, "Incorrect task progress format", e);
				}
			}
			
			userProgress.put(uuid, data);
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
		for(Entry<UUID,int[]> entry : userProgress.entrySet())
		{
			NBTTagCompound pJson = new NBTTagCompound();
			pJson.setString("uuid", entry.getKey().toString());
			NBTTagList pArray = new NBTTagList();
			for(int i : entry.getValue())
			{
				pArray.appendTag(new NBTTagInt(i));
			}
			pJson.setTag("data", pArray);
			progArray.appendTag(pJson);
		}
		json.setTag("userProgress", progArray);
		
		return json;
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
	public float getParticipation(UUID uuid)
	{
		if(requiredItems.size() <= 0)
		{
			return 1F;
		}
		
		float total = 0F;
		
		int[] progress = getUsersProgress(uuid);
		for(int i = 0; i < requiredItems.size(); i++)
		{
			BigItemStack rStack = requiredItems.get(i);
			total += progress[i] / (float)rStack.stackSize;
		}
		
		return total / (float)requiredItems.size();
	}

	@Override
	public IGuiPanel getTaskGui(IGuiRect rect, IQuest quest)
	{
	    return new PanelTaskRetrieval(rect, quest, this);
	}
	
	@Override
	public boolean canAcceptItem(UUID owner, IQuest quest, ItemStack stack)
	{
		if(owner == null || stack == null || stack.stackSize <= 0 || !consume || isComplete(owner) || requiredItems.size() <= 0)
		{
			return false;
		}
		
		int[] progress = getUsersProgress(owner);
		
		for(int j = 0; j < requiredItems.size(); j++)
		{
			BigItemStack rStack = requiredItems.get(j);
			
			if(progress[j] >= rStack.stackSize) continue;
			
			if(ItemComparison.StackMatch(rStack.getBaseStack(), stack, !ignoreNBT, partialMatch) || ItemComparison.OreDictionaryMatch(rStack.getOreIngredient(), rStack.GetTagCompound(), stack, !ignoreNBT, partialMatch))
			{
				return true;
			}
		}
		
		return false;
	}
	
	@Override
	public ItemStack submitItem(UUID owner, IQuest quest, ItemStack input)
	{
		if(owner == null || input == null || !consume || isComplete(owner)) return input;
		
		ItemStack stack = input.copy();
		
		int[] progress = getUsersProgress(owner);
		boolean updated = false;
		
		for(int j = 0; j < requiredItems.size(); j++)
		{
			BigItemStack rStack = requiredItems.get(j);
			
			if(progress[j] >= rStack.stackSize) continue;

			int remaining = rStack.stackSize - progress[j];
			
			if(ItemComparison.StackMatch(rStack.getBaseStack(), stack, !ignoreNBT, partialMatch) || ItemComparison.OreDictionaryMatch(rStack.getOreIngredient(), rStack.GetTagCompound(), stack, !ignoreNBT, partialMatch))
			{
				int removed = Math.min(stack.stackSize, remaining);
				stack.stackSize -= removed;
				progress[j] += removed;
				updated = true;
				if(stack.stackSize <= 0)
                {
                    stack = null;
                    break;
                }
			}
		}
		
		if(updated)
        {
            setUserProgress(owner, progress);
        }
		
		return stack;
	}
	
	@Override
	@SideOnly(Side.CLIENT)
	public GuiScreen getTaskEditor(GuiScreen parent, IQuest quest)
	{
		return null;
	}

	@Override
	public void setUserProgress(UUID uuid, int[] progress)
	{
		userProgress.put(uuid, progress);
	}
	
	@Override
	public int[] getUsersProgress(UUID... users)
	{
		int[] progress = new int[requiredItems.size()];
		
		for(UUID uuid : users)
		{
			int[] tmp = userProgress.get(uuid);
			
			if(tmp == null || tmp.length != requiredItems.size()) continue;
			
			for(int n = 0; n < progress.length; n++)
			{
			    if(!consume)
                {
                    progress[n] = Math.max(progress[n], tmp[n]);
                } else
                {
				    progress[n] += tmp[n];
                }
			}
		}
		
		return progress;
	}
	
	public int[] getPartyProgress(UUID uuid)
	{
		IParty party = QuestingAPI.getAPI(ApiReference.PARTY_DB).getUserParty(uuid);
        return getUsersProgress(party == null ? new UUID[]{uuid} : party.getMembers().toArray(new UUID[0]));
	}

	@Override
	public int[] getGlobalProgress()
	{
		int[] total = new int[requiredItems.size()];
		
		for(int[] up : userProgress.values())
		{
			if(up == null || up.length != requiredItems.size()) continue;
			
			for(int i = 0; i < up.length; i++)
			{
				if(!consume)
				{
					total[i] = Math.max(total[i], up[i]);
				} else
				{
					total[i] += up[i];
				}
			}
		}
		
		return total;
	}
}
