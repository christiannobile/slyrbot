/*
 * SlayerBot.java
 * A fully autonomous OSRS Slayer bot using the DreamBot API.
 *
 * Java Concepts Explained:
 * - class: A blueprint for objects. This is where the behavior and data are defined.
 * - method: A block of code that performs a specific task. Defined with `void` or a return type.
 * - void: Means the method does not return anything.
 * - enum: A list of named constants, useful for states.
 * - final: Used to declare constants or variables that should not change.
 * - package: Groups related Java files together.
 */

package org.dreambot.slayerbot;

// Importing the required DreamBot and Java libraries
import org.dreambot.api.methods.Calculations;
import org.dreambot.api.methods.container.impl.bank.Bank;
import org.dreambot.api.methods.container.impl.Inventory;
import org.dreambot.api.methods.dialogues.Dialogues;
import org.dreambot.api.methods.interactive.NPCs;
import org.dreambot.api.methods.interactive.Players;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.methods.skills.Skill;
import org.dreambot.api.methods.skills.Skills;
import org.dreambot.api.methods.walking.impl.Walking;
import org.dreambot.api.methods.widget.Widgets;
import org.dreambot.api.script.AbstractScript;
import org.dreambot.api.script.Category;
import org.dreambot.api.script.ScriptManifest;
import org.dreambot.api.utilities.Logger;
import org.dreambot.api.wrappers.interactive.NPC;
import org.dreambot.api.wrappers.items.Item;
import org.dreambot.api.methods.input.Camera;
import org.dreambot.api.methods.input.mouse.MouseSettings;

import java.util.function.BooleanSupplier;
import java.util.*;

@ScriptManifest(author = "cno", name = "Complete Slayer Bot", version = 1.0, description = "Autonomous Slayer bot", category = Category.COMBAT)
public class SlayerBot extends AbstractScript {

    // Enum: Represents the current state of the bot
    private enum State {
        GET_TASK, TRAVEL, BANK, COMBAT, HEAL, POTIONS, IDLE
    }

    private String currentTask = "";

    // Final: This list of items should never change
    private final List<String> teleportItems = Arrays.asList(
            "Ring of dueling", "Games necklace", "Amulet of glory", "Ring of wealth", "Varrock teleport", "Falador teleport"
    );

    // Final: Gear presets based on combat style
    private final Map<String, List<String>> gearPresets = new HashMap<>() {{
        put("MELEE", Arrays.asList("Fire cape", "Dragon boots", "Abyssal whip", "Rune defender", "Barrows gloves"));
        put("MAGE", Arrays.asList("Ahrim's robetop", "Ahrim's robeskirt", "Occult necklace", "Staff of the dead"));
        put("RANGED", Arrays.asList("Toxic blowpipe", "Black d'hide body", "Black d'hide chaps", "Archer helm"));
    }};

    private long lastAntiBan = 0;

    // Called once at script start
    @Override
    public void onStart() {
        Logger.log("Starting Slayer Bot");
    }

    // Called continuously in a loop during script execution
    @Override
    public int onLoop() {
        // Perform anti-ban every 20–40 seconds
        if (System.currentTimeMillis() - lastAntiBan > Calculations.random(20000, 40000)) {
            performAntiBan();
            lastAntiBan = System.currentTimeMillis();
        }

        // Determine current state and perform actions accordingly
        switch (getState()) {
            case GET_TASK -> getNewSlayerTask();
            case TRAVEL -> travelToTask();
            case BANK -> bankSupplies();
            case HEAL -> healIfNeeded();
            case POTIONS -> usePotions();
            case COMBAT -> fightMonster();
            case IDLE -> sleep(Calculations.random(300, 600));
        }
        return Calculations.random(500, 900);
    }

    // Determines the current state of the bot
    private State getState() {
        if (needNewTask()) return State.GET_TASK;
        if (!hasSupplies()) return State.BANK;
        if (!isAtTaskLocation()) return State.TRAVEL;
        if (shouldHeal()) return State.HEAL;
        if (shouldUsePotions()) return State.POTIONS;
        if (shouldFight()) return State.COMBAT;
        return State.IDLE;
    }

    private boolean needNewTask() {
        return currentTask.isEmpty();
    }

    // Waits until a condition becomes true or timeout hits
    private boolean sleepUntil(BooleanSupplier condition, int timeoutMillis) {
        int waited = 0;
        int interval = 100;
        while (!condition.getAsBoolean() && waited < timeoutMillis) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            waited += interval;
        }
        return condition.getAsBoolean();
    }

    // Gets a new Slayer task from the nearest master
    private void getNewSlayerTask() {
        NPC master = NPCs.closest(npc -> npc.hasAction("Assignment") || npc.hasAction("Get-task"));
        if (master != null && master.interact("Assignment")) {
            sleepUntil(Dialogues::inDialogue, 5000);
            Dialogues.spaceToContinue();
            currentTask = parseSlayerTask();
            Logger.log("New task: " + currentTask);
        }
    }

    // Placeholder: Replace with widget parsing to get actual task
    private String parseSlayerTask() {
        return "Troll";
    }

    private boolean isAtTaskLocation() {
        return false; // To be implemented later
    }

    private void travelToTask() {
        if (!useTeleport()) {
            Logger.log("Walking to task manually");
            Walking.walk(new Tile(2890, 3560)); // Example tile
        }
    }

    private boolean useTeleport() {
        for (String item : teleportItems) {
            if (Inventory.contains(item)) {
                Item teleport = Inventory.get(item);
                if (teleport != null && teleport.interact("Rub")) {
                    sleep(Calculations.random(1000, 1500));
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasSupplies() {
        return Inventory.contains("Manta ray") && Inventory.contains("Super combat potion") && Inventory.contains("Prayer potion");
    }

    private void bankSupplies() {
        if (Bank.open()) {
            sleepUntil(Bank::isOpen, 3000);
            Bank.depositAllExcept(item -> teleportItems.contains(item.getName()));
            withdrawIfNeeded("Manta ray", 20);
            withdrawIfNeeded("Super combat potion", 2);
            withdrawIfNeeded("Prayer potion", 2);
            Bank.close();
        }
    }

    private void withdrawIfNeeded(String item, int amount) {
        if (!Inventory.contains(item)) {
            Bank.withdraw(item, amount);
            sleep(Calculations.random(400, 800));
        }
    }

    private boolean shouldHeal() {
        return Skills.getBoostedLevel(Skill.HITPOINTS) < Skills.getRealLevel(Skill.HITPOINTS) * 0.65;
    }

    private void healIfNeeded() {
        Item food = Inventory.get("Manta ray");
        if (food != null) food.interact("Eat");
    }

    private boolean shouldUsePotions() {
        return Skills.getBoostedLevel(Skill.STRENGTH) <= Skills.getRealLevel(Skill.STRENGTH)
                || Skills.getBoostedLevel(Skill.PRAYER) < 20;
    }

    private void usePotions() {
        if (Inventory.contains("Super combat potion")) Inventory.get("Super combat potion").interact("Drink");
        if (Inventory.contains("Prayer potion")) Inventory.get("Prayer potion").interact("Drink");
    }

    private boolean shouldFight() {
        return NPCs.closest(npc -> npc.getName().equalsIgnoreCase(currentTask) && npc.canReach()) != null;
    }

    private void fightMonster() {
        NPC target = NPCs.closest(npc -> npc.getName().equalsIgnoreCase(currentTask) && npc.canReach());
        if (target != null && !Players.getLocal().isInCombat()) {
            target.interact("Attack");
            sleepUntil(() -> Players.getLocal().isInCombat(), 3000);
        }
    }

    // Simulates human-like behavior to avoid bans
    private void performAntiBan() {
        MouseSettings.setSpeed(Calculations.random(80, 120));
        sleep(Calculations.random(300, 1200));

        Camera.rotateToPitch(Calculations.random(60, 383));
        if (Calculations.random(0, 100) < 30) {
            Camera.rotateToYaw(Calculations.random(0, 360));
        }
    }

    @Override
    public void onExit() {
        Logger.log("Stopping Slayer Bot");
    }
}
