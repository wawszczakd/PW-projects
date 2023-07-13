#ifndef SYSTEM_HPP
#define SYSTEM_HPP

#include <exception>
#include <vector>
#include <unordered_map>
#include <functional>
#include <future>
#include <queue>
#include <semaphore>
#include <set>
#include <tuple>

#include "machine.hpp"

class FulfillmentFailure : public std::exception {
	
};

class OrderNotReadyException : public std::exception {
	
};

class BadOrderException : public std::exception {
	
};

class BadPagerException : public std::exception {
	
};

class OrderExpiredException : public std::exception {
	
};

class RestaurantClosedException : public std::exception {
	
};

// Thrown when a machine returns nullptr, like in demo.cpp.
class NullPointerException : public std::exception {
	
};

struct WorkerReport {
	std::vector<std::vector<std::string>> collectedOrders;
	std::vector<std::vector<std::string>> abandonedOrders;
	std::vector<std::vector<std::string>> failedOrders;
	std::vector<std::string> failedProducts;
};

class Order {
	
};

class CoasterPager {
private:
	friend class System;
	
	unsigned int id;
	std::atomic<bool> *ready;
	std::vector<std::unique_ptr<Product>> *readyProducts;
	
	std::binary_semaphore *waiter;
	std::binary_semaphore *collect;
	std::binary_semaphore *workerWaiter;
	
	std::atomic<bool> *failed;
	
	CoasterPager();
	
public:
	void wait() const;
	
	void wait(unsigned int timeout) const;
	
	[[nodiscard]] unsigned int getId() const;
	
	[[nodiscard]] bool isReady() const;
};

class System {
public:
	typedef std::unordered_map<std::string, std::shared_ptr<Machine>>
	machines_t;
	
	System(machines_t, unsigned int, unsigned int);
	
	std::vector<WorkerReport> shutdown();
	
	std::vector<std::string> getMenu() const;
	
	std::vector<unsigned int> getPendingOrders() const;
	
	std::unique_ptr<CoasterPager> order(std::vector<std::string>);
	
	std::vector<std::unique_ptr<Product>>
	collectOrder(std::unique_ptr<CoasterPager>);
	
	unsigned int getClientTimeout() const;
	
private:
	machines_t machines;
	
	std::unordered_map<std::string, bool> failed;
	
	bool isShut;
	
	unsigned int numberOfWorkers;
	unsigned int clientTimeout;
	
	mutable std::mutex menuMutex;
	
	std::vector<std::thread> workers;
	void workerFunction(const unsigned int &);
	std::counting_semaphore<INT_MAX> workerSemaphore;
	std::vector<WorkerReport> workerReports;
	
	std::atomic<unsigned int> lastCoasterPagerId;
	
	void helperFunction(const std::string &, std::unique_ptr<Product> &,
	                    std::atomic<bool> *, uint8_t &);
	
	std::mutex orderMutex;
	std::queue<std::tuple<std::vector<std::string>,
	                      unsigned int, std::atomic<bool> *,
	                      std::vector<std::unique_ptr<Product>> *,
	                      std::binary_semaphore *, std::binary_semaphore *,
	                      std::binary_semaphore *, std::atomic<bool> *>
	           > orderQueue;
	
	std::unordered_map<std::string, std::binary_semaphore *>
	machinesMutexes;
	
	std::unordered_map<std::string, std::binary_semaphore *>
	waitingQueuesMutexes;
	std::unordered_map<std::string, std::queue<std::pair<std::thread::id,
	std::binary_semaphore *>>> waitingQueues;
	
	mutable std::mutex pendingOrdersMutex;
	std::set<unsigned int> pendingOrders;
	
	std::mutex stuffToDeleteMutex;
	std::vector<std::tuple<std::atomic<bool> *,
	                       std::vector<std::unique_ptr<Product>> *,
	                       std::binary_semaphore *, std::binary_semaphore *,
	                       std::binary_semaphore *, std::atomic<bool> *>
	            > stuffToDelete;
};

#endif // SYSTEM_HPP
