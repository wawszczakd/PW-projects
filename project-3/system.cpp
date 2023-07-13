#include "system.hpp"

CoasterPager::CoasterPager() : id(0) {
	ready = new std::atomic<bool>(false);
	readyProducts = new std::vector<std::unique_ptr<Product>>();
	// Waiter is currently locked. It will be unlocked by a worker, when the
	// order is ready.
	waiter = new std::binary_semaphore(0);
	collect = new std::binary_semaphore(0);
	workerWaiter = new std::binary_semaphore(0);
	failed = new std::atomic<bool>(false);
}

void CoasterPager::wait() const {
	waiter->acquire();
	
	if (failed->load()) {
		throw FulfillmentFailure();
	}
}

void CoasterPager::wait(unsigned int timeout) const {
	waiter->try_acquire_for(std::chrono::milliseconds(timeout));
	
	if (failed->load()) {
		throw FulfillmentFailure();
	}
}

[[nodiscard]] unsigned int CoasterPager::getId() const {
	return id;
}

[[nodiscard]] bool CoasterPager::isReady() const {
	return ready->load();
}

System::System(machines_t _machines, unsigned int _numberOfWorkers,
               unsigned int _clientTimeout) : machines(_machines), failed(),
                                              isShut(false),
                                              numberOfWorkers(_numberOfWorkers),
                                              clientTimeout(_clientTimeout),
                                              menuMutex(),
                                              workers(_numberOfWorkers),
                                              workerSemaphore(0),
                                              workerReports(_numberOfWorkers),
                                              lastCoasterPagerId(0),
                                              orderMutex(), orderQueue(),
                                              machinesMutexes(),
                                              waitingQueuesMutexes(),
                                              waitingQueues(),
                                              pendingOrdersMutex(),
                                              pendingOrders(),
                                              stuffToDeleteMutex(),
                                              stuffToDelete() {
	for (const auto &[product, machine] : machines) {
		failed.insert(std::make_pair(product, false));
		machinesMutexes.insert(std::make_pair(product,
		                       new std::binary_semaphore(1)));
		waitingQueuesMutexes.insert(std::make_pair(product,
		                            new std::binary_semaphore(1)));
		waitingQueues.insert(std::make_pair(product,
		                     std::queue<std::pair<std::thread::id,
		                     std::binary_semaphore *>>()));
		machine->start();
	}
	
	for (unsigned int id = 0; id < numberOfWorkers; id++) {
		workers[id] = std::thread { [this, id] { workerFunction(id); } };
	}
}

std::vector<WorkerReport> System::shutdown() {
	menuMutex.lock();
	if (isShut) {
		menuMutex.unlock();
		return workerReports;
	}
	isShut = true;
	menuMutex.unlock();
	
	for (unsigned int i = 0; i < numberOfWorkers; i++) {
		// This is necessary for workers to not stay in the infinite loop.
		workerSemaphore.release();
	}
	for (unsigned int i = 0; i < numberOfWorkers; i++) {
		workers[i].join();
	}
	
	menuMutex.lock();
	for (const auto &[product, machine] : machines) {
		if (!failed.find(product)->second) {
			machine->stop();
		}
	}
	menuMutex.unlock();
	
	// Cleaning.
	for (auto &[product, machineMutex] : machinesMutexes) {
		delete machineMutex;
	}
	for (auto &[product, waitingQueueMutex] : waitingQueuesMutexes) {
		delete waitingQueueMutex;
	}
	for (auto &[ready, readyProducts, waiter, collect, workerWaiter, orderFailed] : stuffToDelete) {
		delete ready;
		delete readyProducts;
		delete waiter;
		delete collect;
		delete workerWaiter;
		delete orderFailed;
	}
	
	return workerReports;
}

std::vector<std::string> System::getMenu() const {
	menuMutex.lock();
	
	if (isShut) {
		menuMutex.unlock();
		return {};
	}
	
	std::vector<std::string> result;
	for (const auto &[product, machine] : machines) {
		if (!failed.find(product)->second) {
			result.push_back(product);
		}
	}
	
	menuMutex.unlock();
	
	return result;
}

std::vector<unsigned int> System::getPendingOrders() const {
	std::vector<unsigned int> result;
	
	pendingOrdersMutex.lock();
	for (auto id : pendingOrders) {
		result.push_back(id);
	}
	pendingOrdersMutex.unlock();
	
	return result;
}

std::unique_ptr<CoasterPager> System::order(std::vector<std::string> products) {
	menuMutex.lock();
	
	if (isShut) {
		menuMutex.unlock();
		throw RestaurantClosedException();
	}
	
	if (products.empty()) {
		menuMutex.unlock();
		throw BadOrderException();
	}
	
	for (const auto &product : products) {
		if (machines.find(product) == machines.end() ||
		    failed.find(product)->second) {
			menuMutex.unlock();
			throw BadOrderException();
		}
	}
	
	menuMutex.unlock();
	
	orderMutex.lock();
	
	std::unique_ptr<CoasterPager> result =
	std::unique_ptr<CoasterPager>(new CoasterPager());;
	result->id = lastCoasterPagerId++;
	
	orderQueue.push(std::make_tuple(products, result->id, result->ready,
	                                result->readyProducts, result->waiter,
	                                result->collect, result->workerWaiter,
	                                result->failed));
	
	orderMutex.unlock();
	
	// Notifying a worker, that a new order has been made.
	workerSemaphore.release();
	
	stuffToDeleteMutex.lock();
	stuffToDelete.push_back(std::make_tuple(result->ready,
	                                        result->readyProducts,
	                                        result->waiter, result->collect,
	                                        result->workerWaiter,
	                                        result->failed));
	stuffToDeleteMutex.unlock();
	
	return result;
}

std::vector<std::unique_ptr<Product>> System::collectOrder(
                                   std::unique_ptr<CoasterPager> coasterPager) {
	if (!coasterPager) {
		throw BadPagerException();
	}
	
	if (!coasterPager->ready->load()) {
		throw OrderNotReadyException();
	}
	
	if (coasterPager->collect->try_acquire()) {
		pendingOrdersMutex.lock();
		pendingOrders.erase(coasterPager->getId());
		pendingOrdersMutex.unlock();
		
		if (coasterPager->failed->load()) {
			throw FulfillmentFailure();
		}
		
		std::vector<std::unique_ptr<Product>>
		result(coasterPager->readyProducts->size());
		for (unsigned int i = 0; i < coasterPager->readyProducts->size(); i++) {
			result[i] = std::move(coasterPager->readyProducts->at(i));
		}
		
		coasterPager->workerWaiter->release();
		
		return result;
	}
	else {
		throw OrderExpiredException();
	}
}

unsigned int System::getClientTimeout() const {
	return clientTimeout;
}

void System::workerFunction(const unsigned int &id) {
	while (true) {
		workerSemaphore.acquire();
		
		orderMutex.lock();
		if (!orderQueue.empty()) {
			auto [products, orderId, ready, readyProducts, waiter, collect,
			      workerWaiter, orderFailed] = orderQueue.front();
			orderQueue.pop();
			orderMutex.unlock();
			
			readyProducts->resize(products.size());
			std::vector<uint8_t> resigned(products.size());
			
			std::vector<std::thread> helpers(products.size());
			for (unsigned int i = 0; i < products.size(); i++) {
				std::string &product = products[i];
				std::unique_ptr<Product> &readyProduct = readyProducts->at(i);
				uint8_t &resignedRef = resigned[i];
				
				helpers[i] = std::thread { [this, &product, &readyProduct,
				                            &orderFailed, &resignedRef] {
				helperFunction(product, std::ref(readyProduct), orderFailed,
				               std::ref(resignedRef)); } };
			}
			
			for (unsigned int i = 0; i < products.size(); i++) {
				helpers[i].join();
			}
			
			if (orderFailed->load()) {
				ready->exchange(true);
				collect->release();
				waiter->release();
				
				workerReports[id].failedOrders.push_back(products);
				
				// Returning products.
				for (unsigned int i = 0; i < products.size(); i++) {
					if (readyProducts->at(i)) {
						std::binary_semaphore *machineMutex =
						machinesMutexes.find(products[i])->second;
						machineMutex->acquire();
						
						try {
							machines.find(products[i])->second->returnProduct(
							std::move(readyProducts->at(i)));
						}
						catch (const MachineFailure &e) {}
						
						machineMutex->release();
					}
					else if (!resigned[i]) {
						workerReports[id].failedProducts.push_back(products[i]);
					}
				}
			}
			else {
				ready->exchange(true);
				collect->release();
				waiter->release();
				
				pendingOrdersMutex.lock();
				pendingOrders.insert(orderId);
				pendingOrdersMutex.unlock();
				
				// Waiting for the client to collect.
				workerWaiter->try_acquire_for(
				std::chrono::milliseconds(clientTimeout));
				
				if (collect->try_acquire()) {
					// The client did not collect the order, thus the products
					// must be returned.
					
					workerReports[id].abandonedOrders.push_back(products);
					
					pendingOrdersMutex.lock();
					pendingOrders.erase(orderId);
					pendingOrdersMutex.unlock();
					
					// Returning products.
					for (unsigned int i = 0; i < products.size(); i++) {
						std::binary_semaphore *machineMutex =
						machinesMutexes.find(products[i])->second;
						machineMutex->acquire();
						
						try {
							machines.find(products[i])->second->returnProduct(
							std::move(readyProducts->at(i)));
						}
						catch (const MachineFailure &e) {}
						
						machineMutex->release();
					}
				}
				else {
					// The client collected the order.
					workerReports[id].collectedOrders.push_back(products);
				}
			}
		}
		else {
			orderMutex.unlock();
			if (isShut) {
				break;
			}
		}
	}
}

void System::helperFunction(const std::string &product,
                            std::unique_ptr<Product> &readyProduct,
                            std::atomic<bool> *orderFailed,
                            uint8_t &resignedRef) {
	std::binary_semaphore *mut = waitingQueuesMutexes.find(product)->second;
	mut->acquire();
	
	std::queue<std::pair<std::thread::id, std::binary_semaphore *>>
	&waitingQueue = waitingQueues.find(product)->second;
	
	std::binary_semaphore *mySemaphore = new std::binary_semaphore(0);
	waitingQueue.push(std::make_pair(std::this_thread::get_id(), mySemaphore));
	
	bool amIFirst = (waitingQueue.size() == 1u);
	mut->release();
	
	if (!amIFirst) {
		mySemaphore->acquire();
	}
	// Now, I am first.
	
	menuMutex.lock();
	bool isFailed = failed[product];
	menuMutex.unlock();
	
	if (!isFailed && !orderFailed->load()) {
		std::shared_ptr<Machine> machine = machines.find(product)->second;
		
		try {
			readyProduct = machine->getProduct();
			if (!readyProduct) {
				throw NullPointerException();
			}
		}
		catch (const MachineFailure &e) {
			menuMutex.lock();
			failed[product] = true;
			menuMutex.unlock();
			orderFailed->exchange(true);
		}
	}
	else {
		orderFailed->exchange(true);
		resignedRef = 1;
	}
	
	mut->acquire();
	
	waitingQueue.pop();
	
	std::binary_semaphore *nxt = nullptr;
	if (!waitingQueue.empty()) {
		nxt = waitingQueue.front().second;
	}
	
	mut->release();
	
	if (nxt) {
		// Notifying the next one that now he can safely get the product.
		nxt->release();
	}
	
	delete mySemaphore;
}
