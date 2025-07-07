package org.dreambot.slayerbot;

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
import org.dreambot.api.methods.MethodProvider;
import org.dreambot.api.wrappers.interactive.GameObject;
import org.dreambot.api.utilities.Timer;
import org.dreambot.api.methods.map.Tile;

import java.util.*;

public class SlayerTaskManager {

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

    private final SlayerTeleportHelper teleportHelper = new SlayerTeleportHelper();

    private static final int AMMO_RESTOCK_THRESHOLD = 20;

    // ... rest of your class remains unchanged ...

    public void handleSlayerTask(String monsterName) {
        MethodProvider.log("=== Slayer Task Handling started for monster: " + monsterName + " ===");

        String combatStyle = determineCombatStyle(monsterName);
        MethodProvider.log("Determined combat style: " + combatStyle);

        equipBestGear(monsterName, combatStyle);
        manageInventory(combatStyle);
        if ("ranged".equals(combatStyle)) manageAmmo();
        setupCannon();
        buyMissingItems(combatStyle);

        boolean teleported = teleportHelper.teleportToTask(monsterName);
        if (!teleported) {
            MethodProvider.log("Teleport failed or unavailable, walking to Slayer task area.");
            walkToTaskArea(monsterName);
        }

        MethodProvider.log("=== Slayer Task Handling completed for monster: " + monsterName + " ===");
    }

    private String determineCombatStyle(String monsterName) {
        monsterName = monsterName.toLowerCase();
        if (monsterName.contains("dragon") || monsterName.contains("kurask")) return "ranged";
        if (monsterName.contains("dust devil") || monsterName.contains("black demon")) return "magic";
        return "melee";
    }

    private void equipBestGear(String monsterName, String combatStyle) {
        MethodProvider.log("Equipping gear for combat style: " + combatStyle);
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
                        MethodProvider.sleepUntil(() -> Equipment.contains(gear), 4000);
                        MethodProvider.log("Equipped " + gear);
                    }
                } else {
                    MethodProvider.log("Missing gear item in inventory: " + gear);
                }
            }
        }
    }

    private void manageInventory(String combatStyle) {
        MethodProvider.log("Managing inventory for combat style: " + combatStyle);

        List<String> neededItems = INVENTORY_SETUP.getOrDefault(combatStyle, Collections.emptyList());

        // Bank all low dose potions and withdraw full dose potions
        for (String potionBase : POTION_BASE_NAMES) {
            // Bank partial dose potions (1 or 2 doses)
            for (int dose = 1; dose <= 2; dose++) {
                String lowDose = potionBase + "(" + dose + ")";
                if (Inventory.contains(lowDose)) {
                    MethodProvider.log("Banking low dose potion: " + lowDose);
                    Bank.depositAll(lowDose);
                    MethodProvider.sleepUntil(() -> !Inventory.contains(lowDose), 3000);
                }
            }
            // Withdraw full dose potions
            String fullDose = potionBase + "(4)";
            if (!Inventory.contains(fullDose)) {
                if (Bank.contains(fullDose)) {
                    Bank.withdraw(fullDose, 1);
                    MethodProvider.sleepUntil(() -> Inventory.contains(fullDose), 4000);
                } else {
                    MethodProvider.log("No full dose potion found in bank: " + fullDose);
                    buyFromGrandExchange(fullDose, 1);
                }
            }
        }

        // Withdraw other needed items from bank
        for (String itemName : neededItems) {
            if (!Inventory.contains(itemName) && Bank.contains(itemName)) {
                MethodProvider.log("Withdrawing item from bank: " + itemName);
                Bank.withdraw(itemName, 1);
                MethodProvider.sleepUntil(() -> Inventory.contains(itemName), 4000);
            }
        }
    }

    private void manageAmmo() {
        MethodProvider.log("Managing ammo for ranged combat");

        for (String ammoName : AMMO_ITEMS) {
            int currentCount = Inventory.count(ammoName);
            if (currentCount < AMMO_RESTOCK_THRESHOLD) {
                MethodProvider.log("Ammo low (" + currentCount + "), restocking " + ammoName);
                if (Bank.contains(ammoName)) {
                    Bank.withdraw(ammoName, 100);
                    MethodProvider.sleepUntil(() -> Inventory.count(ammoName) > currentCount, 5000);
                } else {
                    MethodProvider.log("No ammo in bank, trying to buy " + ammoName);
                    buyFromGrandExchange(ammoName, 100);
                }
            }
        }
    }

    private void setupCannon() {
        MethodProvider.log("Checking cannon setup...");

        if (isCannonSetUp()) {
            MethodProvider.log("Cannon already set up.");
            return;
        }

        if (Inventory.contains(CANNON_NAME)) {
            Item cannon = Inventory.get(CANNON_NAME);
            if (cannon != null && cannon.interact("Setup")) {
                MethodProvider.sleep(5000);
                MethodProvider.log("Cannon setup initiated.");
            }
        } else {
            MethodProvider.log("No cannon in inventory to set up.");
        }
    }

    private boolean isCannonSetUp() {
        List<GameObject> cannonObjects = MethodProvider.getGameObjects().all(gameObject -> gameObject != null && gameObject.getName() != null && gameObject.getName().equalsIgnoreCase(CANNON_NAME));
        return !cannonObjects.isEmpty();
    }

    private void buyMissingItems(String combatStyle) {
        MethodProvider.log("Checking and buying missing items from Grand Exchange...");

        List<String> itemsToCheck = new ArrayList<>(INVENTORY_SETUP.getOrDefault(combatStyle, Collections.emptyList()));

        if ("ranged".equals(combatStyle)) {
            itemsToCheck.addAll(AMMO_ITEMS);
        }

        List<String> gearList;
        switch (combatStyle) {
            case "ranged":
                gearList = RANGED_GEAR.getOrDefault("default", Collections.emptyList());
                break;
            case "magic":
                gearList = MAGIC_GEAR.getOrDefault("default", Collections.emptyList());
                break;
            default:
                gearList = MELEE_GEAR.getOrDefault("default", Collections.emptyList());
                break;
        }
        itemsToCheck.addAll(gearList);

        for (String itemName : itemsToCheck) {
            if (!Inventory.contains(itemName) && !Bank.contains(itemName)) {
                MethodProvider.log("Item missing: " + itemName + ", attempting to buy.");
                buyFromGrandExchange(itemName, 1);
            }
        }
    }

    private void buyFromGrandExchange(String itemName, int quantity) {
        MethodProvider.log("Attempting to buy " + quantity + "x " + itemName + " from Grand Exchange...");
        List<GrandExchangeItem> items = GrandExchange.getItems(itemName);
        if (items.isEmpty()) {
            MethodProvider.log("No Grand Exchange item found for: " + itemName);
            return;
        }
        GrandExchangeItem geItem = items.get(0);
        if (GrandExchange.open()) {
            GrandExchange.buy(geItem, quantity, geItem.getPrice());
            MethodProvider.sleepUntil(() -> Inventory.contains(itemName), 10000);
        }
        GrandExchange.close();
    }

    private void walkToTaskArea(String monsterName) {
        MethodProvider.log("Walking to Slayer task area for " + monsterName);
        // You should implement tile coordinates or pathfinding here based on monsterName.
        // Placeholder example:
        Tile taskTile = getTaskAreaTile(monsterName);
        if (taskTile != null) {
            Walking.walk(taskTile);
            MethodProvider.sleepUntil(() -> Players.getLocal().getTile().distance(taskTile) < 5, 20000);
        } else {
            MethodProvider.log("No known tile for task area: " + monsterName);
        }
    }

    private Tile getTaskAreaTile(String monsterName) {
        // Placeholder: map monsters to their task area tiles
        if (monsterName.toLowerCase().contains("kurask")) {
            return new Tile(2843, 2972, 0); // Example: Kurasks in Fremennik Slayer Dungeon
        }
        if (monsterName.toLowerCase().contains("dust devil")) {
            return new Tile(3164, 3856, 0); // Example: Dust Devils in Smoke Dungeon
        }
        if (monsterName.toLowerCase().contains("black demon")) {
            return new Tile(2515, 4632, 0); // Example: Black Demons near the Catacombs of Kourend
        }
        // Add more monster to tile mappings here
        return null;
    }

    // Additional helper classes or methods can be added below

    // Inner class stub for teleporting (you should implement as needed)
    private static class SlayerTeleportHelper {
        public boolean teleportToTask(String monsterName) {
            // Implement teleport logic here, returning true if teleport succeeded.
            return false;
        }
    }
}
