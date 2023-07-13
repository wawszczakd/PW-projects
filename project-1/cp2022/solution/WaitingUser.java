/**
 * University of Warsaw
 * Concurrent Programming Course 2022/2023
 * Java Assignment
 *
 * Author: Dominik Wawszczak (dw440014@students.mimuw.edu.pl)
 */

package cp2022.solution;

import java.util.concurrent.Semaphore;

import cp2022.solution.MyWorkplace;

public class WaitingUser {
	private final long userId;
	private final MyWorkplace from;
	private final Semaphore wait;
	
	public WaitingUser() {
		this.userId = 0;
		this.from = null;
		this.wait = new Semaphore(0);
	}
	
	public WaitingUser(long userId, MyWorkplace from) {
		this.userId = userId;
		this.from = from;
		this.wait = new Semaphore(0);
	}
	
	public final long getUserId() {
		return this.userId;
	}
	
	public final MyWorkplace getFrom() {
		return this.from;
	}
	
	public void waitAcquire() {
		try {
			this.wait.acquire();
		} catch (InterruptedException e) {
			throw new RuntimeException("panic: unexpected thread interruption");
		}
	}
	
	public void waitRelease() {
		this.wait.release();
	}
}
