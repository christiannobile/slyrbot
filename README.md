
# Slayer Bot – Fully Explained & Modularized

This project is a modular, fully-commented version of the DreamBot Slayer Bot. The goal is to teach and demonstrate Java principles while keeping the Slayer bot logic clean and extendable.

## 🔧 Java Concepts Explained

### 📦 Classes
- A **class** is a blueprint for creating objects. It groups variables (fields) and methods (functions that perform actions). In this code, `SlayerBot`, `BankModule`, `CombatModule` are all classes.

### 🔁 Methods
- A **method** is a function defined inside a class that performs a specific task. For example, `fightMonster()` handles the combat logic. Every method has a return type, name, and parameters.

### 🎯 Void
- The keyword `void` means the method does **not return a value**. It's used when you want to perform an action like `log()` or `attack()`, but you don't need anything back.

### 🧱 final
- The `final` keyword means that a variable **cannot be reassigned** once it's set. For example, teleport item lists or constants are marked `final`.

### 🔤 Enum (States)
- `State` is an `enum` – a type used to define a collection of constants (GET_TASK, TRAVEL, COMBAT, etc.). We use it to control what action the bot takes in each loop.

---

## 📁 Folder Structure
```
org/dreambot/slayerbot/
├── SlayerBot.java        # Main script file
├── modules/
│   ├── BankModule.java   # Handles banking and supply logic
│   ├── CombatModule.java # Manages monster fighting
│   ├── TravelModule.java # Teleporting and walking logic
│   └── SlayerManager.java# Task handling, state machine
```

---

## 🧠 How it works
- `SlayerBot.java` runs a loop.
- It uses `SlayerManager` to decide the current state (what to do).
- Then it calls the correct module (`CombatModule`, `TravelModule`, etc.) to perform the task.

---

## 🛠 How to Use

1. Put the files in your script’s directory (`org.dreambot.slayerbot`).
2. Compile or reload the script in your DreamBot script editor.

---

If you want to add more behaviors, simply add new modules and connect them via the `SlayerManager`.



## 🛠 TO DO 
- Compile initial build
- Create the other classes
- Add more gear, items, tele support
- Verify if the project can run autonomously, add remaining functionality
- ...
