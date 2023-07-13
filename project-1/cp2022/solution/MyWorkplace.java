/**
 * University of Warsaw
 * Concurrent Programming Course 2022/2023
 * Java Assignment	
 *
 * Author: Dominik Wawszczak (dw440014@students.mimuw.edu.pl)
 */

package cp2022.solution;

import java.util.LinkedList;
import java.util.concurrent.Semaphore;
import static java.util.Objects.isNull;

import cp2022.base.WorkplaceId;
import cp2022.base.Workplace;
import cp2022.solution.WaitingUser;

public class MyWorkplace extends Workplace {
	private final Workplace workplace;
	private final MyWorkshop myWorkshop;
	private final int n;
	private final Semaphore mutex;
	private final Semaphore isSafe;
	private final LinkedList<WaitingUser> waitingUsers;
	private boolean isUsed;
	private long userId;
	private MyWorkplace prev;
	private MyWorkplace next;
	private boolean onCycle;
	private int myTime;
	private int enterTime;
	
	public MyWorkplace(Workplace workplace, MyWorkshop myWorkshop, int n) {
		super(workplace.getId());
		this.workplace = workplace;
		this.myWorkshop = myWorkshop;
		this.n = n;
		this.mutex = new Semaphore(1);
		this.isSafe = new Semaphore(1);
		this.waitingUsers = new LinkedList<WaitingUser>();
		this.isUsed = false;
		this.userId = 0;
		this.prev = null;
		this.next = null;
		this.onCycle = false;
		this.myTime = -1;
		this.enterTime = -1;
	}
	
	public void mutexAcquire() {
		try {
			this.mutex.acquire();
		} catch (InterruptedException e) {
			throw new RuntimeException("panic: unexpected thread interruption");
		}
	}
	
	public void mutexRelease() {
		this.mutex.release();
	}
	
	public void isSafeAcquire() {
		try {
			this.isSafe.acquire();
		} catch (InterruptedException e) {
			throw new RuntimeException("panic: unexpected thread interruption");
		}
	}
	
	public void isSafeRelease() {
		this.isSafe.release();
	}
	
	public int getMyTime() {
		return this.myTime;
	}
	
	public void setMyTime(int value) {
		this.myTime = value;
	}
	
	public void setEnterTime(int value) {
		this.enterTime = value;
	}
	
	@Override
	public void use() {
		if (!isNull(this.prev)) {
			// this user has finished switchTo(), thus the user occupying
			// the previous workplace may start using it
			this.prev.isSafeRelease();
		}
		if (this.myTime != -1) {
			this.myWorkshop.removeMyTime(this.myTime);
		}
		if (this.enterTime != -1) {
			this.myWorkshop.removeMyTime(this.enterTime);
			this.setEnterTime(-1);
		}
		
		this.isSafeAcquire();
		// the previous user has finished switchTo() / leave()
		this.workplace.use();
		this.isSafeRelease();
		
		this.myTime = this.myWorkshop.getTimer() + 2 * this.n - 1;
		this.myWorkshop.addMyTime(this.myTime);
	}
	
	public boolean isUsed() {
		return this.isUsed;
	}
	
	public long getUserId() {
		return this.userId;
	}
	
	public void stopUsing() {
		this.isUsed = false;
		this.userId = 0;
		this.prev = null;
		this.next = null;
		this.onCycle = false;
	}
	
	public void startUsing(long userId, MyWorkplace prev) {
		this.isUsed = true;
		this.userId = userId;
		this.prev = prev;
		this.next = null;
		this.onCycle = false;
	}
	
	public WaitingUser addToQueue(long userId, MyWorkplace from) {
		WaitingUser result = new WaitingUser(userId, from);
		this.waitingUsers.addLast(result);
		return result;
	}
	
	public WaitingUser getFirstUser() {
		return this.waitingUsers.pollFirst();
	}
	
	public WaitingUser removeFromQueue(MyWorkplace from) {
		WaitingUser result = null;
		for (WaitingUser waitingUser : this.waitingUsers) {
			if (from.equals(waitingUser.getFrom())) {
				result = waitingUser;
				break;
			}
		}
		this.waitingUsers.remove(result);
		return result;
	}
	
	public MyWorkplace getNext() {
		return this.next;
	}
	
	public void setNext(MyWorkplace next) {
		this.next = next;
	}
	
	public void setOnCycle(boolean value) {
		this.onCycle = value;
	}
	
	public boolean getOnCycle() {
		return this.onCycle;
	}
}
