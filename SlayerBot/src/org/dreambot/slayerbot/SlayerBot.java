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

    private static final Map<String, List<String>> MELEE_GEAR = Map.of(
            "default", List.of("Dragon Scimitar", "Rune Platebody", "Rune Platelegs", "Dragon Defender", "Amulet of Strength")
            // add more monster-specific melee gear here
    );
    private static final Map<String, List<String>> RANGED_GEAR = Map.of(
            "default", List.of("Karil's Crossbow", "Black D'hide Body", "Black D'hide Chaps", "Archers Ring", "Ava's Accumulator")
            // add more monster-specific ranged gear here
    );
    private static final Map<String, List<String>> MAGIC_GEAR = Map.of(
            "default", List.of("Mystic Hat", "Mystic Robe Top", "Mystic Robe Bottom", "Occult Necklace", "Ahrim's Staff")
            // add more monster-specific magic gear here
    );

    private static final Map<String, List<String>> INVENTORY_SETUP = Map.of(
            "melee", List.of("Saradomin brew(4)", "Super restore(4)", "Sharks", "Dragon bones", "Dwarf cannon", "Cannonball"),
            "ranged", List.of("Ranging potion(4)", "Super restore(4)", "Sharks", "Rune arrows"),
            "magic", List.of("Magic potion(4)", "Super restore(4)", "Sharks", "Law rune", "Fire rune")
    );

    private static final List<String> AMMO_ITEMS = List.of("Rune arrows", "Dragon arrows", "Bolt racks");

    private static final List<String> POTION_BASE_NAMES = List.of("Saradomin brew", "Super restore", "Ranging potion", "Magic potion");

    private static final String CANNON_NAME = "Dwarf cannon";
    private static final String CANNONBALL_NAME = "Cannonball";

    private final SlayerTeleportHelper teleportHelper = new SlayerTeleportHelper();

    private static final int AMMO_RESTOCK_THRESHOLD = 20;

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
        List<String> gearList = switch (combatStyle) {
            case "ranged" -> RANGED_GEAR.getOrDefault(monsterName.toLowerCase(), RANGED_GEAR.get("default"));
            case "magic" -> MAGIC_GEAR.getOrDefault(monsterName.toLowerCase(), MAGIC_GEAR.get("default"));
            default -> MELEE_GEAR.getOrDefault(monsterName.toLowerCase(), MELEE_GEAR.get("default"));
        };

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

        List<String> gearList = switch (combatStyle) {
            case "ranged" -> RANGED_GEAR.getOrDefault("default", Collections.emptyList());
            case "magic" -> MAGIC_GEAR.getOrDefault("default", Collections.emptyList());
            default -> MELEE_GEAR.getOrDefault("default", Collections.emptyList());
        };
        itemsToCheck.addAll(gearList);

        for (String itemName : itemsToCheck) {
            if (!Inventory.contains(itemName) && !Equipment.contains(itemName) && !Bank.contains(itemName)) {
                MethodProvider.log("Item missing: " + itemName + ", buying from Grand Exchange...");
                buyFromGrandExchange(itemName, 1);
            }
        }
    }

    private void buyFromGrandExchange(String itemName, int quantity) {
        MethodProvider.log("Attempting to buy " + quantity + "x " + itemName + " from Grand Exchange.");

        if (!GrandExchange.isOpen()) {
            GrandExchange.open();
            MethodProvider.sleepUntil(GrandExchange::isOpen, 5000);
        }

        if (GrandExchange.isOpen()) {
            GrandExchangeOffer offer = GrandExchange.createBuyOffer(itemName, quantity);
            if (offer != null) {
                offer.setPrice(calculatePrice(itemName));
                offer.submit();
                MethodProvider.log("Buy offer submitted for " + itemName);
                Timer timer = new Timer(60000);
                while (!offer.isFinished() && !timer.expired()) {
                    MethodProvider.sleep(1000);
                }
                if (offer.isFinished()) {
                    MethodProvider.log("Buy offer for " + itemName + " completed.");
                    GrandExchange.collect();
                } else {
                    MethodProvider.log("Buy offer for " + itemName + " timed out.");
                }
            } else {
                MethodProvider.log("Failed to create buy offer for " + itemName);
            }
            GrandExchange.close();
        }
    }

    private int calculatePrice(String itemName) {
        // Placeholder: returns a price with a 10% margin above GE market price
        int marketPrice = GrandExchange.getPrice(itemName);
        int price = (int) (marketPrice * 1.10);
        MethodProvider.log("Calculated buy price for " + itemName + ": " + price);
        return price;
    }

    private void walkToTaskArea(String monsterName) {
        // This method would contain walking logic based on the monster's slayer area
        MethodProvider.log("Walking to Slayer task area for monster: " + monsterName);
        // Placeholder example
        Tile taskTile = getTaskAreaTile(monsterName);
        if (taskTile != null) {
            Walking.walk(taskTile);
            MethodProvider.sleepUntil(() -> Players.localPlayer().getTile().distance(taskTile) < 5, 15000);
        }
    }

    private Tile getTaskAreaTile(String monsterName) {
        // Map monster name to Slayer task area tile for walking fallback
        return switch (monsterName.toLowerCase()) {
            case "dust devil" -> new Tile(3328, 3838, 0);
            case "kurask" -> new Tile(2850, 3546, 0);
            default -> new Tile(3000, 3200, 0);
        };
    }

    // You can extend this helper or replace with your teleport code
    static class SlayerTeleportHelper {
        boolean teleportToTask(String monsterName) {
            // Try teleporting with jewelry, spells, or other means
            MethodProvider.log("Attempting teleport to task area for " + monsterName);

            // Example: Teleport using Slayer ring or Teleport tablets
            if (Inventory.contains("Slayer ring")) {
                Item ring = Inventory.get("Slayer ring");
                if (ring != null && ring.interact("Rub")) {
                    MethodProvider.sleepUntil(() -> Players.localPlayer().isAnimating(), 5000);
                    MethodProvider.sleep(3000); // Wait for teleport
                    return true;
                }
            }

            // Add more teleport logic here (spells, tablets, etc.)

            return false; // Teleport not available
        }
    }
}
