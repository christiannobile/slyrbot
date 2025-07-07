package org.dreambot.slayerbot;

import org.dreambot.api.methods.MethodProvider;
import org.dreambot.api.methods.container.impl.Inventory;
import org.dreambot.api.methods.container.impl.bank.Bank;
import org.dreambot.api.methods.container.impl.equipment.Equipment;
import org.dreambot.api.methods.grandexchange.GrandExchange;
import org.dreambot.api.methods.grandexchange.GrandExchangeItem;
import org.dreambot.api.methods.magic.Spellbook;
import org.dreambot.api.methods.walking.impl.Walking;
import org.dreambot.api.methods.interactive.Players;
import org.dreambot.api.methods.skills.Skill;
import org.dreambot.api.wrappers.items.Item;
import org.dreambot.api.wrappers.interactive.GameObject;
import org.dreambot.api.utilities.Timer;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.script.AbstractScript;
import org.dreambot.api.script.ScriptManifest;

import java.util.*;

@ScriptManifest(author = "YourName", name = "Slayer Bot", version = 1.0, description = "Automates Slayer tasks")
public class SlayerBot extends AbstractScript {

    private SlayerTaskManager taskManager;

    @Override
    public void onStart() {
        log("Starting Slayer Bot...");
        taskManager = new SlayerTaskManager(this);
    }

    @Override
    public int onLoop() {
        // Example: get current Slayer task monster name from config or API
        String currentTaskMonster = getCurrentSlayerTaskMonster();

        if (currentTaskMonster != null && !currentTaskMonster.isEmpty()) {
            taskManager.handleSlayerTask(currentTaskMonster);
        } else {
            log("No current Slayer task assigned.");
            sleep(5000);
        }

        return 3000; // loop every 3 seconds
    }

    private String getCurrentSlayerTaskMonster() {
        // TODO: Implement your logic to get current task monster name
        // For now, a test dummy:
        return "Black Demon";
    }

    // ========================
    // Inner class: SlayerTaskManager
    // ========================

    public static class SlayerTaskManager {

        private static final Map<String, List<String>> MELEE_GEAR = new HashMap<>();
        private static final Map<String, List<String>> RANGED_GEAR = new HashMap<>();
        private static final Map<String, List<String>> MAGIC_GEAR = new HashMap<>();
        private static final Map<String, List<String>> INVENTORY_SETUP = new HashMap<>();
        private static final List<String> AMMO_ITEMS;
        private static final List<String> POTION_BASE_NAMES;

        private static final String CANNON_NAME = "Dwarf cannon";
        private static final String CANNONBALL_NAME = "Cannonball";

        static {
            MELEE_GEAR.put("default", Arrays.asList("Dragon Scimitar", "Rune Platebody", "Rune Platelegs", "Dragon Defender", "Amulet of Strength"));
            // add more monster-specific melee gear here if needed

            RANGED_GEAR.put("default", Arrays.asList("Karil's Crossbow", "Black D'hide Body", "Black D'hide Chaps", "Archers Ring", "Ava's Accumulator"));
            // add more monster-specific ranged gear here if needed

            MAGIC_GEAR.put("default", Arrays.asList("Mystic Hat", "Mystic Robe Top", "Mystic Robe Bottom", "Occult Necklace", "Ahrim's Staff"));
            // add more monster-specific magic gear here if needed

            INVENTORY_SETUP.put("melee", Arrays.asList("Saradomin brew(4)", "Super restore(4)", "Sharks", "Dragon bones", "Dwarf cannon", "Cannonball"));
            INVENTORY_SETUP.put("ranged", Arrays.asList("Ranging potion(4)", "Super restore(4)", "Sharks", "Rune arrows"));
            INVENTORY_SETUP.put("magic", Arrays.asList("Magic potion(4)", "Super restore(4)", "Sharks", "Law rune", "Fire rune"));

            AMMO_ITEMS = Collections.unmodifiableList(Arrays.asList("Rune arrows", "Dragon arrows", "Bolt racks"));
            POTION_BASE_NAMES = Collections.unmodifiableList(Arrays.asList("Saradomin brew", "Super restore", "Ranging potion", "Magic potion"));
        }

        private final MethodProvider methodProvider;
        private final SlayerTeleportHelper teleportHelper = new SlayerTeleportHelper();

        private static final int AMMO_RESTOCK_THRESHOLD = 20;

        public SlayerTaskManager(MethodProvider methodProvider) {
            this.methodProvider = methodProvider;
        }

        public void handleSlayerTask(String monsterName) {
            methodProvider.log("=== Slayer Task Handling started for monster: " + monsterName + " ===");

            String combatStyle = determineCombatStyle(monsterName);
            methodProvider.log("Determined combat style: " + combatStyle);

            equipBestGear(monsterName, combatStyle);
            manageInventory(combatStyle);
            if ("ranged".equals(combatStyle)) manageAmmo();
            setupCannon();
            buyMissingItems(combatStyle);

            boolean teleported = teleportHelper.teleportToTask(monsterName);
            if (!teleported) {
                methodProvider.log("Teleport failed or unavailable, walking to Slayer task area.");
                walkToTaskArea(monsterName);
            }

            methodProvider.log("=== Slayer Task Handling completed for monster: " + monsterName + " ===");
        }

        private String determineCombatStyle(String monsterName) {
            monsterName = monsterName.toLowerCase();
            if (monsterName.contains("dragon") || monsterName.contains("kurask")) return "ranged";
            if (monsterName.contains("dust devil") || monsterName.contains("black demon")) return "magic";
            return "melee";
        }

        private void equipBestGear(String monsterName, String combatStyle) {
            methodProvider.log("Equipping gear for combat style: " + combatStyle);
            List<String> gearList;
            switch (combatStyle) {
                case "ranged":
                    gearList = RANGED_GEAR.getOrDefault(monsterName.toLowerCase(), RANGED_GEAR.get("default"));
                    break;
                case "magic":
                    gearList = MAGIC_GEAR.getOrDefault(monsterName.toLowerCase(), MAGIC_GEAR.get("default"));
                    break;
                default:
                    gearList = MELEE_GEAR.getOrDefault(monsterName.toLowerCase(), MELEE_GEAR.get("default"));
                    break;
            }

            for (String gear : gearList) {
                if (!Equipment.contains(gear)) {
                    Item item = Inventory.get(gear);
                    if (item != null) {
                        if (item.interact("Wear")) {
                            methodProvider.sleepUntil(() -> Equipment.contains(gear), 4000);
                            methodProvider.log("Equipped " + gear);
                        }
                    } else {
                        methodProvider.log("Missing gear item in inventory: " + gear);
                    }
                }
            }
        }

        private void manageInventory(String combatStyle) {
            methodProvider.log("Managing inventory for combat style: " + combatStyle);

            List<String> neededItems = INVENTORY_SETUP.getOrDefault(combatStyle, Collections.emptyList());

            // Bank all low dose potions and withdraw full dose potions
            for (String potionBase : POTION_BASE_NAMES) {
                // Bank partial dose potions (1 or 2 doses)
                for (int dose = 1; dose <= 2; dose++) {
                    String lowDose = potionBase + "(" + dose + ")";
                    if (Inventory.contains(lowDose)) {
                        methodProvider.log("Banking low dose potion: " + lowDose);
                        Bank.depositAll(lowDose);
                        methodProvider.sleepUntil(() -> !Inventory.contains(lowDose), 3000);
                    }
                }
                // Withdraw full dose potions
                String fullDose = potionBase + "(4)";
                if (!Inventory.contains(fullDose)) {
                    if (Bank.contains(fullDose)) {
                        Bank.withdraw(fullDose, 1);
                        methodProvider.sleepUntil(() -> Inventory.contains(fullDose), 4000);
                    } else {
                        methodProvider.log("No full dose potion found in bank: " + fullDose);
                        buyFromGrandExchange(fullDose, 1);
                    }
                }
            }

            // Withdraw other needed items from bank
            for (String itemName : neededItems) {
                if (!Inventory.contains(itemName) && Bank.contains(itemName)) {
                    methodProvider.log("Withdrawing item from bank: " + itemName);
                    Bank.withdraw(itemName, 1);
                    methodProvider.sleepUntil(() -> Inventory.contains(itemName), 4000);
                }
            }
        }

        private void manageAmmo() {
            methodProvider.log("Managing ammo for ranged combat");

            for (String ammoName : AMMO_ITEMS) {
                int currentCount = Inventory.count(ammoName);
                if (currentCount < AMMO_RESTOCK_THRESHOLD) {
                    methodProvider.log("Ammo low (" + currentCount + "), restocking " + ammoName);
                    if (Bank.contains(ammoName)) {
                        Bank.withdraw(ammoName, 100);
                        methodProvider.sleepUntil(() -> Inventory.count(ammoName) > currentCount, 5000);
                    } else {
                        methodProvider.log("No ammo in bank, trying to buy " + ammoName);
                        buyFromGrandExchange(ammoName, 100);
                    }
                }
            }
        }

        private void setupCannon() {
            methodProvider.log("Checking cannon setup...");

            if (isCannonSetUp()) {
                methodProvider.log("Cannon is already set up.");
                return;
            }

            if (!Inventory.contains(CANNON_NAME)) {
                methodProvider.log("No cannon in inventory, trying to withdraw from bank.");
                if (Bank.contains(CANNON_NAME)) {
                    Bank.withdraw(CANNON_NAME, 1);
                    methodProvider.sleepUntil(() -> Inventory.contains(CANNON_NAME), 5000);
                } else {
                    methodProvider.log("No cannon in bank, buying from Grand Exchange.");
                    buyFromGrandExchange(CANNON_NAME, 1);
                }
            }

            if (!Inventory.contains(CANNONBALL_NAME)) {
                methodProvider.log("No cannonballs in inventory, withdrawing or buying.");
                if (Bank.contains(CANNONBALL_NAME)) {
                    Bank.withdraw(CANNONBALL_NAME, 1000);
                    methodProvider.sleepUntil(() -> Inventory.contains(CANNONBALL_NAME), 5000);
                } else {
                    buyFromGrandExchange(CANNONBALL_NAME, 1000);
                }
            }

            if (Inventory.contains(CANNON_NAME)) {
                methodProvider.log("Placing cannon...");
                GameObject cannonObject = methodProvider.getGameObjects().closest(CANNON_NAME);
                if (cannonObject == null) {
                    methodProvider.log("Placing cannon from inventory...");
                    if (Inventory.get(CANNON_NAME).interact("Set-up")) {
                        methodProvider.sleepUntil(() -> isCannonSetUp(), 10000);
                    }
                } else {
                    methodProvider.log("Cannon object found nearby.");
                }
            }
        }

        private boolean isCannonSetUp() {
            List<GameObject> cannonObjects = methodProvider.getGameObjects().all(gameObject ->
                    gameObject != null && gameObject.getName() != null && gameObject.getName().equalsIgnoreCase(CANNON_NAME)
            );
            return !cannonObjects.isEmpty();
        }

        private void buyMissingItems(String combatStyle) {
            List<String> neededItems = INVENTORY_SETUP.getOrDefault(combatStyle, Collections.emptyList());
            for (String itemName : neededItems) {
                if (!Inventory.contains(itemName) && !Bank.contains(itemName)) {
                    methodProvider.log("Buying missing item from Grand Exchange: " + itemName);
                    buyFromGrandExchange(itemName, 1);
                }
            }
        }

        private void buyFromGrandExchange(String itemName, int quantity) {
            methodProvider.log("Attempting to buy " + quantity + "x " + itemName + " from Grand Exchange...");
            GrandExchangeItem geItem = GrandExchange.getItems().first(itemName);
            if (geItem == null) {
                methodProvider.log("Grand Exchange does not have item: " + itemName);
                return;
            }

            if (GrandExchange.isOpen() || GrandExchange.open()) {
                GrandExchange.placeBuyOffer(geItem, quantity, geItem.getGuidePrice());
                methodProvider.sleepUntil(() -> Inventory.contains(itemName) || Bank.contains(itemName), 30000);
                GrandExchange.close();
                methodProvider.log("Bought " + itemName + " from Grand Exchange.");
            } else {
                methodProvider.log("Failed to open Grand Exchange.");
            }
        }

        private void walkToTaskArea(String monsterName) {
            methodProvider.log("Walking to task area for: " + monsterName);
            // Replace with your actual destination logic
            Tile destination = new Tile(3200, 3200, 0);
            Walking.walk(destination);
            methodProvider.sleepUntil(() -> Players.localPlayer().getTile().distance(destination) < 5, 15000);
        }

        // Basic Teleport Helper
        private static class SlayerTeleportHelper {

            public boolean teleportToTask(String monsterName) {
                // TODO: implement your teleportation logic based on monsterName and your available teleports
                // Example:
                // if (monsterName.equalsIgnoreCase("Black Demon")) {
                //     return castVarrockTeleport();
                // }
                return false;
            }

            private boolean castVarrockTeleport() {
                // Example method to cast Varrock teleport
                // if (Spellbook.getCurrentSpellbook() == Spellbook.NORMAL && Magic.canCast(VarrockTeleportSpell)) {
                //     Magic.castSpell(VarrockTeleportSpell);
                //     return true;
                // }
                return false;
            }
        }
    }
}
