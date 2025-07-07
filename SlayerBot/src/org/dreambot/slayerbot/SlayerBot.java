package org.dreambot.slayerbot;

import org.dreambot.api.script.Category;
import org.dreambot.api.methods.Calculations;
import org.dreambot.api.methods.container.impl.bank.Bank;
import org.dreambot.api.methods.container.impl.equipment.Equipment;
import org.dreambot.api.methods.dialogues.Dialogues;
import org.dreambot.api.methods.interactive.GameObjects;
import org.dreambot.api.methods.item.GroundItems;
import org.dreambot.api.methods.container.impl.Inventory;
import org.dreambot.api.methods.login.LoginUtility;
import org.dreambot.api.methods.map.Area;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.methods.prayer.Prayer;
import org.dreambot.api.methods.skills.Skill;
import org.dreambot.api.methods.MethodProvider;
import org.dreambot.api.methods.tabs.Tab;
import org.dreambot.api.methods.tabs.Tabs;
import org.dreambot.api.script.AbstractScript;
import org.dreambot.api.script.ScriptManifest;
import org.dreambot.api.script.listener.ChatListener;
import org.dreambot.api.wrappers.interactive.NPC;
import org.dreambot.api.wrappers.items.Item;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ScriptManifest(
        author = "SlayerDev",
        name = "SlayerBot",
        version = 3.0,
        description = "Fully autonomous slayer bot with banking, GE, travel, combat, and anti-ban",
        category = Category.COMBAT
)
public class SlayerBot extends AbstractScript implements ChatListener {

    private String currentTask = "";
    private int taskAmount = 0;
    private int killsDone = 0;
    private long lastTaskTime = 0;

    private final Area geArea = new Area(3164, 3487, 3171, 3492);
    private final Random random = new Random();

    private final List<String> foodList = Arrays.asList("shark", "lobster", "monkfish", "swordfish", "manta ray");
    private final List<String> potionList = Arrays.asList("strength potion", "super strength potion", "ranging potion", "super ranging potion");

    @Override
    public void onStart() {
        log("SlayerBot started.");
        handleLoginAndBankPin();
    }

    private void handleLoginAndBankPin() {
        if (getClient().isLoggedIn()) {
            if (Bank.isPinActive()) {
                Bank.enterPin("0000"); // Replace with your pin here
                MethodProvider.sleepUntil(() -> !Bank.isPinActive(), 10000);
            }
            return;
        }
        if (LoginUtility.canLogin()) {
            LoginUtility.login();
            MethodProvider.sleepUntil(() -> getClient().isLoggedIn(), 60000);
        }
    }

    @Override
    public int onLoop() {
        if (!LoginUtility.isLoggedIn()) {
            handleLoginAndBankPin();
            return Calculations.random(3000, 5000);
        }

        if (getLocalPlayer().isDead()) {
            handleDeath();
            return Calculations.random(5000, 7000);
        }

        antiBanActions();

        // If task completed or expired or no current task -> get new task
        if ((killsDone >= taskAmount && taskAmount > 0) || currentTask.isEmpty() || (System.currentTimeMillis() - lastTaskTime > 60 * 60 * 1000)) {
            log("Task completed or expired or no task. Getting new Slayer task...");
            currentTask = "";
            taskAmount = 0;
            killsDone = 0;
            getNewTask();
            return Calculations.random(1500, 2500);
        }

        // Travel if not at task area
        if (!isAtTaskArea()) {
            if(!travelToTask()) {
                teleportToTask();
            }
            return Calculations.random(1000, 2000);
        }

        pickUpLoot();

        if (Inventory.isFull()) {
            bankLoot();
            return Calculations.random(1000, 1500);
        }

        if (needToEat()) {
            eatFood();
            return Calculations.random(800, 1200);
        }

        if (needToDrinkPotion()) {
            drinkPotionIfNeeded();
            return Calculations.random(700, 1000);
        }

        usePrayer();

        switchCombatStyleAndEquip();

        fightMonster();

        if (!hasSupplies()) {
            log("Out of supplies, pausing script.");
            stop();
            return -1;
        }

        return Calculations.random(600, 900);
    }

    private void getNewTask() {
        Tile mazchnaTile = new Tile(3095, 3511);
        getWalking().walk(mazchnaTile);
        MethodProvider.sleepUntil(() -> getLocalPlayer().distance(mazchnaTile) < 5, 10000);
        NPC slayerMaster = getNpcs().closest("Mazchna");
        if (slayerMaster != null && slayerMaster.interact("Assignment")) {
            MethodProvider.sleepUntil(Dialogues::inDialogue, 5000);
            Dialogues.continueDialogue();
            MethodProvider.sleep(3000);
            // Wait for chat message with task, parse in onGameMessage
        }
        lastTaskTime = System.currentTimeMillis();
    }

    private boolean isAtTaskArea() {
        NPC monster = getNpcs().closest(n -> n.getName().equalsIgnoreCase(currentTask));
        return monster != null && monster.distance() < 30;
    }

    private boolean travelToTask() {
        log("Travelling to task area...");
        NPC monster = getNpcs().closest(currentTask);
        if (monster != null) {
            getWalking().walk(monster.getTile());
            return MethodProvider.sleepUntil(this::isAtTaskArea, 15000);
        }
        return false;
    }

    private void teleportToTask() {
        log("Teleporting to task area (stub)...");
        // TODO: Implement teleports or jewelry use here
    }

    private void fightMonster() {
        NPC monster = getNpcs().closest(n -> n.getName().equalsIgnoreCase(currentTask) && !n.isInCombat());
        if (monster != null && monster.interact("Attack")) {
            MethodProvider.sleepUntil(() -> getCombat().isInCombat(), 5000);
            if (getCombat().isInCombat()) {
                log("Fighting " + currentTask);
                waitForFightEnd(monster);
                killsDone++;
                log("Kills done: " + killsDone + "/" + taskAmount);
            }
        }
    }

    private void waitForFightEnd(NPC monster) {
        MethodProvider.sleepUntil(() -> !monster.exists() || !monster.isValid() || !getLocalPlayer().isInCombat(), 30000);
    }

    private void bankLoot() {
        if (Bank.open()) {
            Bank.depositAllExcept(item ->
                    item.getName().toLowerCase().contains("slayer") ||
                            isFood(item) ||
                            isPotion(item)
            );
            Bank.close();
        } else {
            GameObjects.closest("Bank booth").interact("Bank");
        }
    }

    private boolean needToEat() {
        return getSkills().getBoostedLevel(Skill.HITPOINTS) < 15 && hasFood();
    }

    private boolean needToDrinkPotion() {
        return getSkills().getBoostedLevel(Skill.STRENGTH) < getSkills().getRealLevel(Skill.STRENGTH) + 3 && hasPotion();
    }

    private boolean hasFood() {
        return Inventory.contains(i -> foodList.stream().anyMatch(food -> i.getName().toLowerCase().contains(food)));
    }

    private boolean hasPotion() {
        return Inventory.contains(i -> potionList.stream().anyMatch(potion -> i.getName().toLowerCase().contains(potion)));
    }

    private boolean isFood(Item item) {
        String name = item.getName().toLowerCase();
        return foodList.stream().anyMatch(name::contains);
    }

    private boolean isPotion(Item item) {
        String name = item.getName().toLowerCase();
        return potionList.stream().anyMatch(name::contains);
    }

    private void eatFood() {
        Item food = Inventory.get(i -> foodList.stream().anyMatch(f -> i.getName().toLowerCase().contains(f)));
        if (food != null) {
            log("Eating " + food.getName());
            food.interact("Eat");
            sleep(Calculations.random(600, 900));
        } else {
            buyFromGE(foodList.get(0), 100);
        }
    }

    private void drinkPotionIfNeeded() {
        Item potion = Inventory.get(i -> potionList.stream().anyMatch(p -> i.getName().toLowerCase().contains(p)));
        if (potion != null) {
            log("Drinking " + potion.getName());
            potion.interact("Drink");
        } else {
            buyFromGE(potionList.get(0), 10);
        }
    }

    private void usePrayer() {
        if (!getPrayer().isActive(Prayer.PROTECT_FROM_MELEE) && getLocalPlayer().isInCombat()) {
            getPrayer().toggle(Prayer.PROTECT_FROM_MELEE);
        } else if (getPrayer().isActive(Prayer.PROTECT_FROM_MELEE) && !getLocalPlayer().isInCombat()) {
            getPrayer().toggle(Prayer.PROTECT_FROM_MELEE);
        }
    }

    private void switchCombatStyleAndEquip() {
        // Stub for combat style switching & gear equip based on task
    }

    private void pickUpLoot() {
        List<Item> drops = GroundItems.all(item -> item != null && item.isOnScreen() && item.getName() != null && !item.getName().toLowerCase().contains("bones"));
        for (Item item : drops) {
            if (item.distance() < 5 && !Inventory.isFull()) {
                log("Picking up loot: " + item.getName());
                item.interact("Take");
                sleep(Calculations.random(500, 800));
                break;
            }
        }
    }

    private boolean hasSupplies() {
        return hasFood() && hasPotion();
    }

    private void buyFromGE(String itemName, int quantity) {
        log("Buying " + quantity + " of " + itemName + " from GE.");
        if (!geArea.contains(getLocalPlayer())) {
            getWalking().walk(geArea.getRandomTile());
            MethodProvider.sleepUntil(() -> geArea.contains(getLocalPlayer()), 15000);
        }
        // TODO: Implement GE buying logic
    }

    private void handleDeath() {
        log("Player died. Waiting to respawn...");
        MethodProvider.sleepUntil(() -> !getLocalPlayer().isDead(), 30000);
        Bank.open();
        Bank.depositAll();
        Bank.close();
        getWalking().walk(geArea.getRandomTile());
    }

    private void antiBanActions() {
        int action = random.nextInt(1000);
        if (action < 5) {
            getCamera().rotateTo(random.nextInt(360));
        } else if (action >= 5 && action < 10) {
            Tabs.open(Tab.STATS);
            sleep(800, 1500);
            Tabs.open(Tab.INVENTORY);
        } else if (action >= 10 && action < 15) {
            MethodProvider.sleep(Calculations.random(1000, 3000));
        }
    }

    @Override
    public void onGameMessage(String message) {
        if (message.contains("You have been assigned to kill")) {
            Pattern p = Pattern.compile("You have been assigned to kill (\\d+) (.+?)s?");
            Matcher m = p.matcher(message);
            if (m.find()) {
                taskAmount = Integer.parseInt(m.group(1));
                currentTask = m.group(2);
                killsDone = 0;
                log("New task: Kill " + taskAmount + " " + currentTask + "(s).");
            }
        }
    }

    @Override
    public void onChatMessage(String s, String s1) {
        // Can be used for anti-ban or debugging chat messages
    }

    @Override
    public void onExit() {
        log("SlayerBot stopped.");
    }
}
