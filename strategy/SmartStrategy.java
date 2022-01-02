package mineopoly_two.strategy;

import mineopoly_two.game.Economy;
import mineopoly_two.action.TurnAction;
import mineopoly_two.item.InventoryItem;
import mineopoly_two.tiles.TileType;
import mineopoly_two.util.DistanceUtil;

import java.awt.*;
import java.util.*;
import java.util.List;


public class SmartStrategy implements MinePlayerStrategy {
    protected int boardSize;
    protected int maxInventorySize;
    protected int maxCharge;
    protected int winningScore;
    private boolean isRedPlayer;
    private Random random;


    //everything computed from PlayerBoardView and useful for strategy
    List<Point> marketTileLocations;
    List<Point> rechargeTileLocations;
    List<Point> resourceTileLocations;


    //data that is refreshed when a PlayerBoardView object is passed in
    private Map<Point, InventoryItem> currentItemsOnGround;
    private Point currentLocation;
    private TileType currentTileType;
    private Point currentOtherPlayerLocation;
    private int currentOtherPlayerScore;

    //status
    private List<InventoryItem> inventory;
    private int score;
    private int energy;

    //move to destination to complete the task, energy has been considered
    private SmartStrategyTask task;
    private Point destination;
    private List<Point> taskPath;
    private TurnAction lastTurnAction;


    @Override
    public void initialize(int boardSize, int maxInventorySize, int maxCharge, int winningScore,
                           PlayerBoardView startingBoard, Point startTileLocation, boolean isRedPlayer, Random random) {

        this.boardSize = boardSize;
        this.maxInventorySize = maxInventorySize - 2; //strategy only takes 3 at a time
        this.maxCharge = maxCharge;
        this.winningScore = winningScore;
        this.isRedPlayer = isRedPlayer;
        this.random = random;

        //compute against PlayerBoardView
        if (isRedPlayer) {
            marketTileLocations = findTileLocationsByType(startingBoard, TileType.RED_MARKET);
        } else {
            marketTileLocations = findTileLocationsByType(startingBoard, TileType.BLUE_MARKET);
        }
        rechargeTileLocations = findTileLocationsByType(startingBoard, TileType.RECHARGE);


        refreshCurrentStatus(startingBoard);

        inventory = new ArrayList<>();
        score = 0;
        energy = maxCharge;
        task = SmartStrategyTask.MORE_INVENTORY; //first task for player to work on
        destination = findNearestPoint(currentLocation, resourceTileLocations);
        taskPath = new ArrayList<>(); //add start point to taskPath only at initialization
        taskPath.add(currentLocation);
        taskPath.addAll(getPath(currentLocation, destination));
        lastTurnAction = null; //updated for every call to getTurnAction()
    }

    @Override
    public TurnAction getTurnAction(PlayerBoardView boardView, Economy economy, int currentCharge, boolean isRedTurn) {
        TurnAction actionToTake = null;
        refreshCurrentStatus(boardView);
        energy = currentCharge;

        if (isCurrentTaskCompleted()) {
            setNextTask();
        }

        //MOVE or PICK_UP
        if (currentTileType == TileType.EMPTY) {
            if (getInventoryItem(currentLocation) == null) {
                actionToTake = getMoveAction();
            } else {
                actionToTake = TurnAction.PICK_UP;
            }
        }

        //NULL to get more energy or MOVE
        else if (currentTileType == TileType.RECHARGE) {
            //"You will need to return null for multiple turns in a row to become fully charged while standing on a recharge tile."
            if (task == SmartStrategyTask.RECHARGE) {
                actionToTake = null;
            } else {
                actionToTake = getMoveAction();
            }
        }

        //useless market
        else if (currentTileType == TileType.BLUE_MARKET || currentTileType == TileType.RED_MARKET) {
            actionToTake = getMoveAction();
        }

        //RESOURCE tile
        else if (isResourceTile(currentTileType)) {
            if (task != SmartStrategyTask.MORE_INVENTORY) {
                actionToTake = getMoveAction();
            } else {
                actionToTake = TurnAction.MINE;
            }
        }

        lastTurnAction = actionToTake;
        return actionToTake;
    }

    /**
     * Gets the current location
     *
     * @return direction to move
     */
    public TurnAction getMoveAction() {
        Point start = currentLocation, end = null;

        for (int i = 0; i < taskPath.size(); i++) {
            if (taskPath.get(i).equals(currentLocation)) {
                end = taskPath.get(i + 1);
                break;
            }
        }

        if (end == null) {
            end = taskPath.get(0);
        }

        int dx = end.x - start.x;
        int dy = end.y - start.y;

        if (dx == 1 && dy == 0) {
            return TurnAction.MOVE_RIGHT;
        }

        if (dx == -1 && dy == 0) {
            return TurnAction.MOVE_LEFT;
        }

        if (dx == 0 && dy == -1) {
            return TurnAction.MOVE_DOWN;
        }

        if (dx == 0 && dy == 1) {
            return TurnAction.MOVE_UP;
        }

        return null;
    }

    /**
     * Sets next task for
     */
    public void setNextTask() {
        //if RECHARGE is complete, consider MORE_INVENTORY then SALE
        if (task == SmartStrategyTask.RECHARGE) {

            if (shouldGetMoreInventory()) {
                createNewTask(SmartStrategyTask.MORE_INVENTORY,
                        findNearestPoint(currentLocation, resourceTileLocations));

            } else {
                createNewTask(SmartStrategyTask.SALE,
                        findNearestPoint(currentLocation, marketTileLocations));
            }

        } //if MORE_INVENTORY is completed, consider MORE_INVENTORY then SALE then RECHARGE
        else if (task == SmartStrategyTask.MORE_INVENTORY) {

            if (shouldGetMoreInventory()) {
                createNewTask(SmartStrategyTask.MORE_INVENTORY,
                        findNearestPoint(currentLocation, resourceTileLocations));

            } else if (shouldSale()) {
                createNewTask(SmartStrategyTask.SALE,
                        findNearestPoint(currentLocation, marketTileLocations));

            } else {
                createNewTask(SmartStrategyTask.RECHARGE,
                        findNearestPoint(currentLocation, rechargeTileLocations));
            }

        } //if SALE is complete, consider MORE_INVENTORY then RECHARGE
        else if (task == SmartStrategyTask.SALE) {
            if (shouldGetMoreInventory()) {
                createNewTask(SmartStrategyTask.MORE_INVENTORY,
                        findNearestPoint(currentLocation, resourceTileLocations));
            } else {
                createNewTask(SmartStrategyTask.RECHARGE,
                        findNearestPoint(currentLocation, rechargeTileLocations));
            }
        }
    }

    /**
     * If inventory size is the same as the maximum size, then the player should sell inventory.
     *
     * @return if the player should sell at market
     */
    private boolean shouldSale() {
        return inventory.size() == maxInventorySize;
    }

    /**
     * Determines whether the player should get more resources based on energy and inventory size vs. distance,
     * If not enough energy to get to nearest resource, return false.
     *
     * @return if the player should get more inventory
     */
    private boolean shouldGetMoreInventory() {
        Point nearestResource = findNearestPoint(currentLocation, resourceTileLocations);

        if (nearestResource == null) {
            return false;
        }

        int minMoveToResource = DistanceUtil.getManhattanDistance(nearestResource, currentLocation);

        if (energy < Math.max(minMoveToResource, minimumChargeBeforeRecharge())) {
            return false;
        }

        if (inventory.size() == maxInventorySize) {
            return false;
        }
        return true;
    }

    /**
     * Creates a new task and gets path for the player to do so.
     *
     * @param task        the task needed to be completed
     * @param destination the final destination of the task
     */
    public void createNewTask(SmartStrategyTask task, Point destination) {
        this.task = task;
        this.destination = destination;
        taskPath = getPath(currentLocation, destination);
        lastTurnAction = null;
    }

    @Override
    public void onReceiveItem(InventoryItem itemReceived) {
        inventory.add(itemReceived);
    }


    /**
     * Determines if the task to be done has been completed.
     *
     * @return if the task has been completed
     */
    public boolean isCurrentTaskCompleted() {
        Point lastStop = taskPath.get(taskPath.size() - 1);

        if (!currentLocation.equals(lastStop)) {
            return false;
        }

        if (task == SmartStrategyTask.RECHARGE) {
            return energy >= minimumChargeBeforeLeaveStation();
        }

        if (task == SmartStrategyTask.MORE_INVENTORY) {
            return inventory.size() == maxInventorySize || lastTurnAction == TurnAction.PICK_UP;
        }

        // arrive at market tile, inventory is automatically exchanged to points to end SALE task
        if (task == SmartStrategyTask.SALE) {
            return true;
        }
        return false;
    }

    /**
     * Generates all points from start to end, excluding start,
     * Gets the path for the player to travel.
     *
     * @param start the starting point
     * @param end   the end destination of path
     * @return the points in the path as a list of points
     */
    public List<Point> getPath(Point start, Point end) {
        List<Point> path = new ArrayList<>();
        int dx = 0;
        int dy = 0;

        //checks if starting x coordinate is greater than or less than ending x coordinate
        if (start.x > end.x) {
            dx = -1;

        } else if (start.x < end.x) {
            dx = 1;
        }

        //checks if starting y coordinate is greater than or less than ending y coordinate
        if (start.y > end.y) {
            dy = -1;

        } else if (start.y < end.y) {
            dy = 1;
        }

        //horizontal move
        if (dx == 1) {
            for (int i = start.x + dx; i <= end.x; i = i + dx) {
                path.add(new Point(i, start.y));
            }

        } else if (dx == -1) {
            for (int i = start.x + dx; i >= end.x; i = i + dx) {
                path.add(new Point(i, start.y));
            }
        }

        //vertical move
        if (dy == 1) {
            for (int j = start.y + dy; j <= end.y; j = j + dy) {
                path.add(new Point(end.x, j));
            }

        } else if (dy == -1) {
            for (int j = start.y + dy; j >= end.y; j = j + dy) {
                path.add(new Point(end.x, j));
            }
        }
        return path;
    }

    @Override
    public void onSoldInventory(int totalSellPrice) {
        score += totalSellPrice;
        inventory.clear();
    }

    /**
     * The minimum charge the player should have before they go to recharge station.
     *
     * @return the battery percentage of minimum charge permissible before going to recharge
     */
    public int minimumChargeBeforeRecharge() {
        //because recharge station is in middle, make coefficient based on board size
        return (int) (boardSize * 1.5);
    }

    /**
     * The minimum energy to obtain from recharge station before leaving.
     *
     * @return the battery percentage of minimum charge permissible before leaving recharge
     */
    public int minimumChargeBeforeLeaveStation() {
        //charge to 90% of max before leaving recharge station
        return (int) (maxCharge * 0.9);
    }

    @Override
    public String getName() {
        return "SmartStrategy";
    }

    @Override
    public void endRound(int totalRedPoints, int totalBluePoints) {

    }

    /**
     * Finds coordinates of type of tile needed on board.
     *
     * @param boardView the game board
     * @param tileTypeToFind the type of tile to search for
     * @return the location(s) of tile type
     */
    public List<Point> findTileLocationsByType(PlayerBoardView boardView, TileType tileTypeToFind) {
        List<Point> locations = new ArrayList<>();

        for (int locX = 0; locX < boardSize; locX++)
            for (int locY = 0; locY < boardSize; locY++) {
                TileType tileType = boardView.getTileTypeAtLocation(locX, locY);
                if (tileType == tileTypeToFind) {
                    locations.add(new Point(locX, locY));
                }
            }
        return locations;
    }

    /**
     * Determines if a tile is a resource tile,
     * Resource tile may be diamond, emerald, or ruby.
     *
     * @param tileType type of tile
     * @return if the tile is a resource tile or not
     */
    private boolean isResourceTile(TileType tileType) {
        return tileType == TileType.RESOURCE_DIAMOND
                || tileType == TileType.RESOURCE_EMERALD
                || tileType == TileType.RESOURCE_RUBY;
    }

    /**
     * Puts items from ground into inventory.
     *
     * @param point the location of player item
     * @return current item on ground inventory
     */
    public InventoryItem getInventoryItem(Point point) {
        for (Point inventoryPoint : currentItemsOnGround.keySet()) {
            if (point.equals(inventoryPoint)) {
                return currentItemsOnGround.get(inventoryPoint);
            }
        }
        return null;
    }

    /**
     * Finds nearest point on board from the player,
     * Points can be markets, resources, recharge stations
     *
     * @param start  the starting point
     * @param points list of points on board
     * @return the nearest point
     */
    public Point findNearestPoint(Point start, Collection<Point> points) {
        int minDistance = Integer.MAX_VALUE;
        Point nearestPoint = null;
        for (Point p : points) {

            if (p.equals(start)) {
                continue;
            }

            int distance = DistanceUtil.getManhattanDistance(start, p);

            if (distance < minDistance) {
                minDistance = distance;
                nearestPoint = p;
            }
        }
        return nearestPoint;
    }

    /**
     * Updates the status of game board.
     *
     * @param boardView the game board
     */
    private void refreshCurrentStatus(PlayerBoardView boardView) {
        currentItemsOnGround = boardView.getItemsOnGround();
        currentLocation = boardView.getYourLocation();
        currentTileType = boardView.getTileTypeAtLocation(currentLocation.x, currentLocation.y);
        currentOtherPlayerLocation = boardView.getOtherPlayerLocation();
        currentOtherPlayerScore = boardView.getOtherPlayerScore();
        resourceTileLocations = findTileLocationsByType(boardView, TileType.RESOURCE_DIAMOND);
        resourceTileLocations.addAll(findTileLocationsByType(boardView, TileType.RESOURCE_EMERALD));
        resourceTileLocations.addAll(findTileLocationsByType(boardView, TileType.RESOURCE_RUBY));
    }
}

