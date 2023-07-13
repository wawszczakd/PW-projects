/**
 * University of Warsaw
 * Concurrent Programming Course 2022/2023
 * Java Assignment
 *
 * Author: Dominik Wawszczak (dw440014@students.mimuw.edu.pl)
 */

package cp2022.solution;

import java.util.concurrent.Semaphore;

public class EnteringUser {
	private final long userId;
	private final int myTime;
	private final Semaphore wait;
	
	public EnteringUser() {
		this.userId = 0;
		this.myTime = 0;
		this.wait = new Semaphore(0);
	}
	
	public EnteringUser(long userId, int myTime) {
		this.userId = userId;
		this.myTime = myTime;
		this.wait = new Semaphore(0);
	}
	
	public final long getUserId() {
		return this.userId;
	}
	
	public final int getMyTime() {
		return this.myTime;
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
