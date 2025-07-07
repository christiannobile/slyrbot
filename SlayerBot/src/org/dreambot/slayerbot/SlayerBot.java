package org.dreambot.slayerbot;

import org.dreambot.api.script.Category;
import org.dreambot.api.methods.Calculations;
import org.dreambot.api.methods.container.impl.bank.Bank;
import org.dreambot.api.methods.container.impl.bank.BankMode;
import org.dreambot.api.methods.container.impl.equipment.Equipment;
import org.dreambot.api.methods.dialogues.Dialogues;
import org.dreambot.api.methods.interactive.GameObjects;
import org.dreambot.api.methods.item.GroundItems;
import org.dreambot.api.methods.container.impl.Inventory;
import org.dreambot.api.methods.map.Area;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.methods.prayer.Prayer;
import org.dreambot.api.methods.skills.Skill;
import org.dreambot.api.methods.MethodProvider;
import org.dreambot.api.methods.tabs.Tabs;
import org.dreambot.api.script.AbstractScript;
import org.dreambot.api.script.ScriptManifest;
import org.dreambot.api.script.listener.ChatListener;
import org.dreambot.api.methods.widget.messages.Message;
import org.dreambot.api.utilities.Timer;
import org.dreambot.api.wrappers.interactive.GameObject;
import org.dreambot.api.wrappers.interactive.NPC;
import org.dreambot.api.wrappers.items.Item;
import org.dreambot.api.events.listeners.ChatListener;
import org.dreambot.api.script.events.ChatMessageEvent;

import java.util.Arrays;
import java.util.List;

@ScriptManifest(
        author = "SlayerDev",
        name = "SlayerBot",
        version = 2.0,
        description = "Fully autonomous slayer bot with banking, GE, travel, and combat",
        category = Category.COMBAT
)
public class SlayerBot extends AbstractScript implements ChatListener {

    private String currentTask = "";
    private int taskAmount = 0;
    private Timer taskTimer;
    private final Area geArea = new Area(3164, 3487, 3171, 3492);

    @Override
    public void onStart() {
        log("SlayerBot started.");
        taskTimer = new Timer(60 * 60 * 1000);
    }

    @Override
    public int onLoop() {
        if (!getClient().isLoggedIn()) {
            // Client handles login and bank pin
            return Calculations.random(3000, 5000);
        }

        if (getSkills().getBoostedLevels(Skill.HITPOINTS) < 10) {
            eatFood();
            return Calculations.random(600, 1200);
        }

        if (currentTask.isEmpty() || taskAmount <= 0 || taskTimer.expired()) {
            getNewTask();
            return Calculations.random(1000, 1500);
        }

        if (!isAtTaskArea()) {
            travelToTask();
            return Calculations.random(1000, 2000);
        }

        if (Inventory.isFull()) {
            bankLoot();
            return Calculations.random(800, 1200);
        }

        drinkPotionIfNeeded();
        usePrayer();
        fightMonster();
        performAntiBan();

        return Calculations.random(600, 900);
    }

    private void getNewTask() {
        log("Getting new Slayer task...");
        getWalking().walk(new Tile(3095, 3511)); // Example: Mazchna
        MethodProvider.sleepUntil(() -> getLocalPlayer().distance(new Tile(3095, 3511)) < 5, 10000);
        NPC slayerMaster = getNpcs().closest("Mazchna");
        if (slayerMaster != null && slayerMaster.interact("Assignment")) {
            MethodProvider.sleepUntil(() -> !currentTask.isEmpty(), 8000);
        }
        taskTimer.reset();
    }

    private boolean isAtTaskArea() {
        return getNpcs().closest(n -> n.getName().equalsIgnoreCase(currentTask)) != null;
    }

    private void travelToTask() {
        log("Travelling to task area...");
        getWalking().walk(getClosestTaskTile());
        MethodProvider.sleepUntil(this::isAtTaskArea, 15000);
    }

    private Tile getClosestTaskTile() {
        NPC monster = getNpcs().closest(currentTask);
        if (monster != null) return monster.getTile();
        return new Tile(3200, 3200); // fallback
    }

    private void fightMonster() {
        NPC monster = getNpcs().closest(n -> n.getName().equalsIgnoreCase(currentTask) && !n.isInCombat());
        if (monster != null && monster.interact("Attack")) {
            MethodProvider.sleepUntil(() -> getCombat().isInCombat(), 5000);
        }
    }

    private void bankLoot() {
        if (Bank.open()) {
            Bank.depositAllExcept(item ->
                    item.getName().toLowerCase().contains("slayer") ||
                            item.getName().toLowerCase().contains("food") ||
                            item.getName().toLowerCase().contains("potion")
            );
            Bank.close();
        } else {
            GameObject booth = GameObjects.closest("Bank booth");
            if (booth != null) booth.interact("Bank");
        }
    }

    private void eatFood() {
        Item food = Inventory.get(item ->
                item.getName().toLowerCase().contains("shark") ||
                        item.getName().toLowerCase().contains("lobster")
        );
        if (food != null) {
            food.interact("Eat");
            sleep(Calculations.random(600, 900));
        } else {
            buyFromGE("Shark", 100);
        }
    }

    private void drinkPotionIfNeeded() {
        if (getSkills().getBoostedLevels(Skill.STRENGTH) < getSkills().getRealLevel(Skill.STRENGTH) + 3) {
            Item potion = Inventory.get(i -> i.getName().toLowerCase().contains("strength potion"));
            if (potion != null) {
                potion.interact("Drink");
            } else {
                buyFromGE("Strength potion(4)", 10);
            }
        }
    }

    private void usePrayer() {
        if (!getPrayer().isActive(Prayer.PROTECT_FROM_MELEE)) {
            getPrayer().toggle(Prayer.PROTECT_FROM_MELEE);
        }
    }

    private void buyFromGE(String itemName, int quantity) {
        log("Buying from GE: " + itemName);
        getWalking().walk(geArea.getRandomTile());
        MethodProvider.sleepUntil(() -> geArea.contains(getLocalPlayer()), 10000);
        NPC geClerk = getNpcs().closest("Grand Exchange Clerk");
        if (geClerk != null && geClerk.interact("Exchange")) {
            MethodProvider.sleepUntil(() -> getGrandExchange().isOpen(), 10000);
            if (getGrandExchange().openBuyScreen(0)) {
                getGrandExchange().buyItem(itemName, 1000, quantity); // 1000 = high price
                MethodProvider.sleepUntil(() -> Inventory.contains(itemName), 15000);
            }
        }
    }

    private void performAntiBan() {
        int random = Calculations.random(0, 1000);
        if (random < 10) { // ~1% chance each loop to do an anti-ban action
            // Randomly open tabs
            List<Tabs.Tab> tabs = Arrays.asList(Tabs.Tab.values());
            Tabs.Tab tab = tabs.get(Calculations.random(0, tabs.size() - 1));
            Tabs.open(tab);
            sleep(Calculations.random(500, 1000));
            Tabs.open(Tabs.Tab.INVENTORY);
        } else if (random < 20) {
            // Random camera movement
            int yaw = Calculations.random(0, 360);
            int pitch = Calculations.random(10, 90);
            getCamera().rotateTo(yaw, pitch);
        }
    }

    @Override
    public void onChatMessage(ChatMessageEvent event) {
        String message = event.getMessage();
        if (message.contains("Your slayer task is to kill")) {
            currentTask = message.split("kill ")[1].split("\\.")[0];
            // TODO: parse actual amount from message if possible
            taskAmount = 100;
            log("New task: " + currentTask + " x" + taskAmount);
        }
    }
}
