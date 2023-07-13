/**
 * University of Warsaw
 * Concurrent Programming Course 2022/2023
 * Java Assignment
 *
 * Author: Dominik Wawszczak (dw440014@students.mimuw.edu.pl)
 */

package cp2022.solution;

import java.util.Collection;
import java.util.LinkedList;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import static java.util.Objects.isNull;

import cp2022.base.Workshop;
import cp2022.base.WorkplaceId;
import cp2022.base.Workplace;
import cp2022.solution.MyWorkplace;
import cp2022.solution.WaitingUser;
import cp2022.solution.EnteringUser;

public class MyWorkshop implements Workshop {
	private final ConcurrentSkipListMap<WorkplaceId, MyWorkplace> workplaces;
	private final int n;
	private final ConcurrentSkipListMap<Long, MyWorkplace> occupied;
	private final Semaphore detectingCycle;
	private final AtomicInteger timer;
	private final TreeMap<Integer, Integer> waitTimes;
	private final Semaphore waitTimesMutex;
	private final TreeMap<Integer, EnteringUser> enteringUsers;
	
	public MyWorkshop(Collection<Workplace> workplaces) {
		this.workplaces = new ConcurrentSkipListMap<WorkplaceId, MyWorkplace>();
		this.n = workplaces.size();
		for (Workplace workplace : workplaces) {
			this.workplaces.put(workplace.getId(),
			                    new MyWorkplace(workplace, this, n));
		}
		
		this.occupied = new ConcurrentSkipListMap<Long, MyWorkplace>();
		
		this.detectingCycle = new Semaphore(1);
		
		this.timer = new AtomicInteger(0);
		this.waitTimes = new TreeMap<Integer, Integer>();
		this.waitTimesMutex = new Semaphore(1, true);
		this.enteringUsers = new TreeMap<Integer, EnteringUser>();
	}
	
	private void detectingCycleAcquire() {
		try {
			this.detectingCycle.acquire();
		} catch (InterruptedException e) {
			throw new RuntimeException("panic: unexpected thread interruption");
		}
	}
	
	private void detectingCycleRelease() {
		this.detectingCycle.release();
	}
	
	public int getTimer() {
		return this.timer.get();
	}
	
	public void waitTimesMutexAcquire() {
		try {
			this.waitTimesMutex.acquire();
		} catch (InterruptedException e) {
			throw new RuntimeException("panic: unexpected thread interruption");
		}
	}
	
	public void waitTimesMutexRelease() {
		this.waitTimesMutex.release();
	}
	
	public void addMyTime(int myTime) {
		this.waitTimesMutexAcquire();
		
		int cnt = this.waitTimes.getOrDefault(myTime, 0) + 1;
		this.waitTimes.put(myTime, cnt);
		
		this.waitTimesMutexRelease();
	}
	
	public void removeMyTime(int myTime) {
		this.waitTimesMutexAcquire();
		
		int cnt = this.waitTimes.get(myTime) - 1;
		if (cnt > 0) {
			this.waitTimes.put(myTime, cnt);
		}
		else {
			this.waitTimes.remove(myTime);
		}
		
		if (!this.waitTimes.isEmpty()) {
			// let some users in
			int minTime = this.waitTimes.firstKey();
			while (!this.enteringUsers.isEmpty()) {
				int userTime = this.enteringUsers.firstKey();
				if (userTime > minTime) {
					break;
				}
				EnteringUser enteringUser = this.enteringUsers.remove(userTime);
				enteringUser.waitRelease();
			}
		}
		else {
			// let all users in
			while (!this.enteringUsers.isEmpty()) {
				int userTime = this.enteringUsers.firstKey();
				EnteringUser enteringUser = this.enteringUsers.remove(userTime);
				enteringUser.waitRelease();
			}
		}
		
		this.waitTimesMutexRelease();
	}
	
	public EnteringUser addToQueue(long userId, int myTime) {
		EnteringUser result = new EnteringUser(userId, myTime);
		this.enteringUsers.put(myTime, result);
		return result;
	}
	
	public Workplace enter(WorkplaceId wid) {
		this.waitTimesMutexAcquire();
		
		int myTime = this.timer.incrementAndGet();
		int maxTime = myTime + 2 * this.n - 1;
		int cnt = this.waitTimes.getOrDefault(maxTime, 0) + 1;
		this.waitTimes.put(maxTime, cnt);
		
		long userId = Thread.currentThread().getId();
		MyWorkplace newWorkplace = this.workplaces.get(wid);
		
		if (!this.waitTimes.isEmpty()) {
			int minTime = this.waitTimes.firstKey();
			if (minTime < myTime) {
				// this user has to wait before enetering
				EnteringUser me = this.addToQueue(userId, myTime);
				this.waitTimesMutexRelease();
				me.waitAcquire();
			}
			else {
				this.waitTimesMutexRelease();
			}
		}
		else {
			this.waitTimesMutexRelease();
		}
		
		newWorkplace.mutexAcquire();
		
		if (newWorkplace.isUsed()) {
			WaitingUser me = newWorkplace.addToQueue(userId, null);
			newWorkplace.mutexRelease();
			me.waitAcquire();
			// newWorkplace.mutex will be acquired by the thread that
			// will have called waitRelease()
		}
		
		newWorkplace.startUsing(userId, null);
		newWorkplace.setEnterTime(maxTime);
		this.occupied.put(userId, newWorkplace);
		
		newWorkplace.mutexRelease();
		return newWorkplace;
	}
	
	public Workplace switchTo(WorkplaceId wid) {
		long userId = Thread.currentThread().getId();
		MyWorkplace newWorkplace = this.workplaces.get(wid);
		MyWorkplace oldWorkplace = this.occupied.get(userId);
		
		newWorkplace.mutexAcquire();
		
		if (newWorkplace.equals(oldWorkplace)) {
			newWorkplace.startUsing(userId, null);
			newWorkplace.mutexRelease();
			return newWorkplace;
		}
		
		if (newWorkplace.isUsed()) {
			// detecting a cycle
			newWorkplace.mutexRelease();
			this.detectingCycleAcquire();
			newWorkplace.mutexAcquire();
			
			// possibly, newWorkplace is no longer being used
			if (newWorkplace.isUsed()) {
				LinkedList<MyWorkplace> cycle = new LinkedList<MyWorkplace>();
				MyWorkplace curr = newWorkplace;
				cycle.addLast(curr);
				while (!isNull(curr.getNext())) {
					curr = curr.getNext();
					curr.mutexAcquire();
					cycle.addLast(curr);
				}
				
				if (cycle.peekLast().equals(oldWorkplace)) {
					// cycle detected
					for (MyWorkplace myWorkplace : cycle) {
						myWorkplace.setOnCycle(true);
						myWorkplace.isSafeAcquire();
					}
					
					MyWorkplace from = null;
					for (MyWorkplace myWorkplace : cycle) {
						if (!isNull(from)) {
							WaitingUser tmp = myWorkplace.removeFromQueue(from);
							tmp.waitRelease();
						}
						else {
							newWorkplace.startUsing(userId, oldWorkplace);
							this.occupied.put(userId, newWorkplace);
							
							newWorkplace.mutexRelease();
						}
						from = myWorkplace;
					}
					
					this.detectingCycleRelease();
					return newWorkplace;
				}
				else {
					// cycle not detected
					oldWorkplace.mutexAcquire();
					oldWorkplace.setNext(newWorkplace);
					WaitingUser me = newWorkplace.addToQueue(userId,
					                                         oldWorkplace);
					oldWorkplace.mutexRelease();
					
					for (MyWorkplace myWorkplace : cycle) {
						myWorkplace.mutexRelease();
					}
					
					this.detectingCycleRelease();
					me.waitAcquire();
					// newWorkplace.mutex will be acquired by the thread that
					// will have called waitRelease()
				}
			}
			else {
				this.detectingCycleRelease();
			}
		}
		
		if (newWorkplace.getOnCycle()) {
			newWorkplace.startUsing(userId, oldWorkplace);
			this.occupied.put(userId, newWorkplace);
			
			newWorkplace.mutexRelease();
			
			return newWorkplace;
		}
		else {
			newWorkplace.startUsing(userId, oldWorkplace);
			this.occupied.put(userId, newWorkplace);
			
			newWorkplace.mutexRelease();
			
			oldWorkplace.mutexAcquire();
			
			oldWorkplace.stopUsing();
			
			WaitingUser newUser = oldWorkplace.getFirstUser();
			oldWorkplace.isSafeAcquire();
			if (!isNull(newUser)) {
				newUser.waitRelease();
				// oldWorkplace.mutex will be released by newUser
			}
			else {
				oldWorkplace.mutexRelease();
			}
			
			return newWorkplace;
		}
	}
	
	public void leave() {
		long userId = Thread.currentThread().getId();
		MyWorkplace oldWorkplace = this.occupied.remove(userId);
		
		oldWorkplace.mutexAcquire();
		
		this.occupied.remove(userId);
		this.removeMyTime(oldWorkplace.getMyTime());
		oldWorkplace.setMyTime(-1);
		oldWorkplace.stopUsing();
		
		WaitingUser newUser = oldWorkplace.getFirstUser();
		oldWorkplace.isSafeAcquire();
		if (!isNull(newUser)) {
			newUser.waitRelease();
			// oldWorkplace.mutex will be released by newUser
		}
		else {
			oldWorkplace.mutexRelease();
		}
		
		oldWorkplace.isSafeRelease();
	}
}
