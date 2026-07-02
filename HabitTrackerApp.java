import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class HabitTrackerApp {

    static class HabitNotFoundException extends Exception {
        HabitNotFoundException(String msg) { super(msg); }
    }

    static class DuplicateHabitException extends Exception {
        DuplicateHabitException(String msg) { super(msg); }
    }

    static class InvalidTimeException extends Exception {
        InvalidTimeException(String msg) { super(msg); }
    }

    interface Trackable {
        void markCompleted(LocalDate date);
        void resetStreak();
        int getStreak();
        List<HabitHistoryEntry> getHistory();
    }

    interface Remindable {
        void setReminder(LocalTime time, boolean daily) throws InvalidTimeException;
        void clearReminder();
        Optional<LocalTime> getReminderTime();
    }

    enum HabitType { DAILY, WEEKLY }

    static class HabitHistoryEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        LocalDate date;
        boolean completed;

        HabitHistoryEntry(LocalDate date, boolean completed) {
            this.date = date;
            this.completed = completed;
        }

        public String toString() {
            return date + " -> " + (completed ? "COMPLETED" : "MISSED");
        }
    }

    static abstract class Habit implements Trackable, Remindable, Serializable {
        private static final long serialVersionUID = 1L;

        protected int id;
        protected String name;
        protected String description;
        protected HabitType type;
        protected LocalDate createdAt;
        protected int streak;
        protected int longestStreak;
        protected LocalDate lastCompletedDate;
        protected List<HabitHistoryEntry> history = new ArrayList<>();
        protected transient LocalTime reminderTime;
        protected transient boolean reminderDaily;

        Habit(int id, String name, String description, HabitType type) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.type = type;
            this.createdAt = LocalDate.now();
            this.streak = 0;
            this.longestStreak = 0;
        }

        public int getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public HabitType getType() { return type; }
        public LocalDate getCreatedAt() { return createdAt; }
        public int getStreak() { return streak; }
        public int getLongestStreak() { return longestStreak; }
        public LocalDate getLastCompletedDate() { return lastCompletedDate; }
        public List<HabitHistoryEntry> getHistory() { return history; }

        public void markCompleted(LocalDate date) {
            if (lastCompletedDate != null && lastCompletedDate.equals(date)) {
                System.out.println("Habit already marked completed for " + date);
                return;
            }
            history.add(new HabitHistoryEntry(date, true));
            lastCompletedDate = date;

            LocalDate yesterday = date.minusDays(1);
            boolean wasYesterdayCompleted = history.stream()
                    .anyMatch(e -> e.date.equals(yesterday) && e.completed);

            if (wasYesterdayCompleted) streak++;
            else streak = 1;

            if (streak > longestStreak) longestStreak = streak;
            System.out.println("Marked '" + name + "' as completed on " + date + ". Current streak: " + streak);
        }

        public void resetStreak() {
            streak = 0;
            System.out.println("Streak reset for habit: " + name);
        }

        public void setReminder(LocalTime time, boolean daily) throws InvalidTimeException {
            if (time == null) throw new InvalidTimeException("Reminder time cannot be null");
            this.reminderTime = time;
            this.reminderDaily = daily;
        }

        public void clearReminder() {
            this.reminderTime = null;
            this.reminderDaily = false;
        }

        public Optional<LocalTime> getReminderTime() {
            return Optional.ofNullable(reminderTime);
        }

        public void setName(String name) { this.name = name; }
        public void setDescription(String desc) { this.description = desc; }

        public String toString() {
            return String.format("ID:%d | %s (%s) | Streak:%d | Longest:%d | Reminder:%s",
                    id, name, type, streak, longestStreak,
                    reminderTime == null ? "none" : reminderTime.toString());
        }
    }

    static class DailyHabit extends Habit {
        DailyHabit(int id, String name, String desc) { super(id, name, desc, HabitType.DAILY); }
    }

    static class WeeklyHabit extends Habit {
        WeeklyHabit(int id, String name, String desc) { super(id, name, desc, HabitType.WEEKLY); }
    }

    static class HabitRepository implements Serializable {
        private static final long serialVersionUID = 1L;
        private Map<Integer, Habit> habits = new HashMap<>();
        private int nextId = 1;

        public Habit addHabit(String name, String desc, HabitType type) throws DuplicateHabitException {
            for (Habit h : habits.values()) {
                if (h.getName().equalsIgnoreCase(name)) throw new DuplicateHabitException("Duplicate habit name");
            }
            Habit h = type == HabitType.DAILY ? new DailyHabit(nextId, name, desc)
                    : new WeeklyHabit(nextId, name, desc);
            habits.put(nextId, h);
            nextId++;
            return h;
        }

        public void editHabit(int id, String newName, String newDesc) throws HabitNotFoundException {
            Habit h = getHabit(id);
            h.setName(newName);
            h.setDescription(newDesc);
        }

        public void deleteHabit(int id) throws HabitNotFoundException {
            if (!habits.containsKey(id)) throw new HabitNotFoundException("Habit id not found: " + id);
            habits.remove(id);
        }

        public Habit getHabit(int id) throws HabitNotFoundException {
            Habit h = habits.get(id);
            if (h == null) throw new HabitNotFoundException("Habit not found: " + id);
            return h;
        }

        public Collection<Habit> listHabits() { return habits.values(); }
        public boolean isEmpty() { return habits.isEmpty(); }
    }

    static class Storage {
        private static final String FILE = "habit_data.ser";

        static void save(HabitRepository repo) {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(FILE))) {
                oos.writeObject(repo);
            } catch (IOException ex) { System.err.println("Failed to save data: " + ex.getMessage()); }
        }

        static HabitRepository load() {
            File f = new File(FILE);
            if (!f.exists()) return new HabitRepository();
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(FILE))) {
                return (HabitRepository) ois.readObject();
            } catch (Exception ex) {
                System.err.println("Failed to load data: " + ex.getMessage());
                return new HabitRepository();
            }
        }
    }

    static class ReminderManager {
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        private final Map<Integer, ScheduledFuture<?>> tasks = new HashMap<>();
        private final HabitRepository repo;

        ReminderManager(HabitRepository repo) { this.repo = repo; }

        public void scheduleAllExisting() {
            for (Habit h : repo.listHabits()) {
                if (h.getReminderTime().isPresent()) {
                    try { scheduleReminder(h.getId(), h.getReminderTime().get(), h.reminderDaily); }
                    catch (Exception e) { System.err.println("Failed to schedule: " + e.getMessage()); }
                }
            }
        }

        public void scheduleReminder(int habitId, LocalTime time, boolean daily) throws HabitNotFoundException {
            Habit h = repo.getHabit(habitId);
            cancelReminderIfExists(habitId);

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime target = LocalDateTime.of(now.toLocalDate(), time);
            if (!target.isAfter(now)) target = target.plusDays(1);
            long delay = Duration.between(now, target).toMillis();

            Runnable task = () -> System.out.println("\n=== REMINDER ===\nHabit: " + h.getName() + "\nTime: " + time + "\nMessage: It's time to do your habit!\n===============");

            ScheduledFuture<?> future;
            if (daily)
                future = scheduler.scheduleAtFixedRate(task, delay, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
            else
                future = scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);

            tasks.put(habitId, future);
            try { h.setReminder(time, daily); } catch (InvalidTimeException ignore) {}
            System.out.println("Scheduled reminder for '" + h.getName() + "' at " + time + (daily ? " (daily)" : ""));
        }

        public void cancelReminderIfExists(int habitId) {
            ScheduledFuture<?> f = tasks.remove(habitId);
            if (f != null) f.cancel(false);
            try { repo.getHabit(habitId).clearReminder(); } catch (Excep
